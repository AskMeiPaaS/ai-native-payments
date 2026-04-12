package com.ayedata.ai.agent;

import com.ayedata.init.UserProfileInitializer;
import com.ayedata.service.AccountBalanceService;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Intent classification engine for PaSS orchestration.
 * <p>
 * Handles Step 1 of the pipeline: classifying user intent into a structured
 * {@link ParsedIntent} via a lightweight LLM call. Also manages pending
 * channel intents for follow-up channel selection.
 *
 * @see ParsedIntent
 * @see PaSSOrchestratorAgent
 */
@Component
public class IntentClassifier {
    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    private static final int CLASSIFIER_MAX_RETRIES = 1;
    private static final long CLASSIFIER_RETRY_DELAY_MS = 2000;

    private final OllamaChatModel chatLanguageModel;
    private final AccountBalanceService accountBalanceService;
    private final List<String> supportedChannels;
    private final Set<String> supportedChannelsUpper;

    /**
     * Per-session cache for intents awaiting channel selection.
     * When classifier returns CHANNEL: UNKNOWN, we store the parsed intent here.
     * On the next message, if it looks like a channel reply, we merge it in
     * instead of re-classifying from scratch (which loses context).
     */
    private final Map<String, ParsedIntent> pendingChannelIntents = new ConcurrentHashMap<>();

    /** Wraps classifier raw text output together with token usage metrics. */
    public record ClassificationResult(ParsedIntent intent, int inputTokens, int outputTokens) {}

    public IntentClassifier(OllamaChatModel chatLanguageModel,
                            AccountBalanceService accountBalanceService,
                            @Value("${app.payment.channels:UPI Lite,UPI,NEFT,RTGS,IMPS,Cheque}") String channelsCsv) {
        this.chatLanguageModel = chatLanguageModel;
        this.accountBalanceService = accountBalanceService;
        this.supportedChannels = Arrays.stream(channelsCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.supportedChannelsUpper = supportedChannels.stream()
                .map(String::toUpperCase).collect(Collectors.toSet());
    }

    /** Well-defined payment channels from env. */
    public List<String> getSupportedChannels() {
        return supportedChannels;
    }

    /**
     * Check if this message is a channel follow-up for a pending intent.
     * If a pending intent exists for the session and the message looks like a channel selection,
     * merges the channel into the cached intent and returns it. Otherwise returns null.
     */
    public ParsedIntent checkPendingChannelIntent(String sessionId, String userIntent) {
        ParsedIntent pending = pendingChannelIntents.remove(sessionId);
        if (pending == null) return null;

        String selectedChannel = extractChannelFromFollowUp(userIntent);
        if (selectedChannel != null) {
            ParsedIntent merged = new ParsedIntent(pending.action(), pending.beneficiary(),
                    pending.amount(), selectedChannel, pending.confidence());
            log.info("Session {}: Merged channel '{}' into pending intent → action={} amount={} beneficiary={}",
                    sessionId, selectedChannel, merged.action(), merged.amount(), merged.beneficiary());
            return merged;
        }
        // Not a channel reply — caller should re-classify the full new message
        return null;
    }

    /**
     * Cache a parsed intent for later channel selection.
     * Called when classifier returns CHANNEL: UNKNOWN for a transactional intent.
     */
    public void cachePendingIntent(String sessionId, ParsedIntent intent) {
        pendingChannelIntents.put(sessionId, intent);
        log.info("Session {}: Cached pending intent for channel selection (action={} amount={} beneficiary={})",
                sessionId, intent.action(), intent.amount(), intent.beneficiary());
    }

    /**
     * Classify user intent via LLM with retry.
     * Returns the parsed intent along with Step 1 token usage metrics.
     *
     * @throws RuntimeException if classification fails after all retries
     */
    public ClassificationResult classify(String sessionId, String userId, String userIntent) {
        String classifierCtx = buildClassifierContext(userId, userIntent);
        log.info("Session {}: Step 1 — classifying intent ({} chars)...", sessionId, classifierCtx.length());

        Exception lastException = null;
        for (int attempt = 0; attempt <= CLASSIFIER_MAX_RETRIES; attempt++) {
            try {
                if (attempt > 0) {
                    log.info("Session {}: Classifier retry {}/{} after {}ms backoff",
                            sessionId, attempt, CLASSIFIER_MAX_RETRIES, CLASSIFIER_RETRY_DELAY_MS);
                    Thread.sleep(CLASSIFIER_RETRY_DELAY_MS);
                }
                ChatRequest req = ChatRequest.builder()
                        .messages(List.of(
                                dev.langchain4j.data.message.SystemMessage.from(buildClassifierPrompt()),
                                dev.langchain4j.data.message.UserMessage.from(classifierCtx)))
                        .build();
                ChatResponse resp = chatLanguageModel.chat(req);
                TokenUsage tu = resp.tokenUsage();
                int in  = (tu != null && tu.inputTokenCount()  != null) ? tu.inputTokenCount()  : 0;
                int out = (tu != null && tu.outputTokenCount() != null) ? tu.outputTokenCount() : 0;
                String raw = resp.aiMessage().text();
                log.info("Session {}: Classifier output: {} (step1 tokens: in={} out={})",
                        sessionId, raw.replace("\n", " | "), in, out);

                ParsedIntent intent = parseClassification(raw);
                log.info("Session {}: Parsed → action={} beneficiary={} amount={} channel={} confidence={}",
                        sessionId, intent.action(), intent.beneficiary(), intent.amount(), intent.channel(), intent.confidence());
                return new ClassificationResult(intent, in, out);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Classification interrupted", ie);
            } catch (Exception e) {
                lastException = e;
                log.warn("Session {}: Classifier attempt {} failed: {}",
                        sessionId, attempt + 1, e.getMessage());
            }
        }
        throw new RuntimeException("Classification failed after " + (CLASSIFIER_MAX_RETRIES + 1) + " attempts", lastException);
    }

    /**
     * Try to extract a payment channel from a short follow-up message like "via UPI", "NEFT", "use RTGS".
     * Returns the canonical channel name if found, or null if the message doesn't look like a channel selection.
     */
    String extractChannelFromFollowUp(String message) {
        if (message == null || message.isBlank()) return null;
        String cleaned = message.strip()
                .replaceAll("(?i)^(via|use|through|using|by|over|select|choose|pick|go with|i want|i'd like|let's go with)\\s+", "")
                .replaceAll("(?i)\\s*(please|channel|payment|mode)\\s*$", "")
                .strip();
        if (cleaned.isEmpty()) return null;
        return supportedChannels.stream()
                .filter(c -> c.equalsIgnoreCase(cleaned))
                .findFirst()
                .orElse(null);
    }

    /**
     * Build a compact context for the intent classifier.
     * Includes registered users, current balance, last 3 transactions,
     * and the raw request — kept concise for the 3B model.
     */
    private String buildClassifierContext(String userId, String userIntent) {
        Map<String, String> registry = UserProfileInitializer.getDemoUserRegistry();
        StringBuilder sb = new StringBuilder();
        sb.append("Users: ");
        for (var entry : registry.entrySet()) {
            if (!entry.getKey().equals(userId)) {
                sb.append(entry.getValue()).append(", ");
            }
        }
        try {
            double balance = accountBalanceService.getCurrentBalance(userId);
            sb.append("\nBalance: ₹").append(String.format("%.0f", balance));
        } catch (Exception ignored) { }
        try {
            var txns = accountBalanceService.getRecentTransactionSummary(userId, 3);
            if (!txns.isEmpty()) {
                sb.append("\nRecent: ").append(String.join("; ", txns));
            }
        } catch (Exception ignored) { }
        sb.append("\nRequest: ").append(userIntent);
        return sb.toString();
    }

    /**
     * Build a dynamic classifier prompt that includes the well-defined channel list
     * from the PAYMENT_CHANNELS env var. The LLM MUST return one of these or UNKNOWN.
     */
    private String buildClassifierPrompt() {
        String channelList = String.join(", ", supportedChannels);
        return """
            Classify the payment request. Reply with EXACTLY these five lines, nothing else:
            ACTION: TRANSFER or RECEIVE or MANDATE or QUERY_BALANCE or QUERY_TRANSACTIONS or QUERY_SEARCH or QUERY
            BENEFICIARY: person name or none
            AMOUNT: number or 0
            CHANNEL: one of [%s] or UNKNOWN
            CONFIDENCE: HIGH or MEDIUM or LOW

            Rules:
            - CHANNEL must be EXACTLY one of: %s. Never invent channel names.
            - If the user explicitly names a channel (e.g. "via UPI", "through NEFT"), return that channel.
            - If the user does NOT specify a channel, return CHANNEL: UNKNOWN.
            - When a person's name appears in a query, use QUERY_SEARCH with BENEFICIARY set to that person.
            - Use QUERY_TRANSACTIONS for listing/filtering transactions without a specific person.
            - Use QUERY_BALANCE only for balance inquiries with no person mentioned.
            - CONFIDENCE: HIGH only for general questions unrelated to payments/banking.
            - CONFIDENCE: MEDIUM when at least the action is clear (TRANSFER, RECEIVE, QUERY_BALANCE, QUERY_TRANSACTIONS, QUERY_SEARCH).
            - CONFIDENCE: LOW when the request is vague or unclear.

            Examples:
            - "what is my balance" → ACTION: QUERY_BALANCE, CONFIDENCE: MEDIUM
            - "show my UPI debits" → ACTION: QUERY_TRANSACTIONS, CONFIDENCE: MEDIUM
            - "how much I owe to Priya" → ACTION: QUERY_SEARCH, BENEFICIARY: Priya, CONFIDENCE: MEDIUM
            - "pay 5000 to Ramesh via UPI" → ACTION: TRANSFER, AMOUNT: 5000, BENEFICIARY: Ramesh, CHANNEL: UPI, CONFIDENCE: MEDIUM
            - "receive 10000" → ACTION: RECEIVE, AMOUNT: 10000, CHANNEL: UNKNOWN, CONFIDENCE: MEDIUM
            - "send money to Ramesh" → ACTION: TRANSFER, BENEFICIARY: Ramesh, AMOUNT: 0, CHANNEL: UNKNOWN, CONFIDENCE: LOW
            - "hello, how are you" → ACTION: QUERY, CONFIDENCE: HIGH
            - "what is PaSS" → ACTION: QUERY, CONFIDENCE: HIGH
            - "transfer 2000 to Priya" → ACTION: TRANSFER, AMOUNT: 2000, BENEFICIARY: Priya, CHANNEL: UNKNOWN, CONFIDENCE: MEDIUM
            """.formatted(channelList, channelList);
    }

    /**
     * Parse the classifier's structured text output into a {@link ParsedIntent}.
     * Tolerant of extra whitespace and minor formatting variations.
     */
    ParsedIntent parseClassification(String raw) {
        String action = "QUERY";
        String beneficiary = "none";
        double amount = 0;
        String channel = "UNKNOWN";
        String confidence = "HIGH";

        String normalized = raw.replaceAll("(?i),\\s*(?=(?:ACTION|BENEFICIARY|AMOUNT|CHANNEL|CONFIDENCE):)", "\n");
        for (String line : normalized.split("\n")) {
            line = line.trim();
            if (line.toUpperCase().startsWith("ACTION:")) {
                action = line.substring(7).trim().toUpperCase();
            } else if (line.toUpperCase().startsWith("BENEFICIARY:")) {
                beneficiary = line.substring(12).trim();
            } else if (line.toUpperCase().startsWith("AMOUNT:")) {
                try {
                    amount = Double.parseDouble(line.substring(7).trim().replaceAll("[^0-9.]", ""));
                } catch (NumberFormatException ignored) { }
            } else if (line.toUpperCase().startsWith("CHANNEL:")) {
                String rawCh = line.substring(8).trim();
                if ("auto".equalsIgnoreCase(rawCh) || "auto-select".equalsIgnoreCase(rawCh)
                        || "unknown".equalsIgnoreCase(rawCh) || rawCh.isBlank()) {
                    channel = "UNKNOWN";
                } else if (supportedChannelsUpper.contains(rawCh.toUpperCase())) {
                    channel = supportedChannels.stream()
                            .filter(c -> c.equalsIgnoreCase(rawCh))
                            .findFirst().orElse(rawCh);
                } else {
                    log.warn("Classifier returned unrecognised channel '{}' — treating as UNKNOWN", rawCh);
                    channel = "UNKNOWN";
                }
            } else if (line.toUpperCase().startsWith("CONFIDENCE:")) {
                confidence = line.substring(11).trim().toUpperCase();
            }
        }
        return new ParsedIntent(action, beneficiary, amount, channel, confidence);
    }

    /** Format a human-readable stage message from a classified intent. */
    public static String formatClassifiedMessage(ParsedIntent intent) {
        if (intent.isQueryTool()) {
            return switch (intent.action()) {
                case "QUERY_BALANCE"      -> "Step 1 · Checking your account balance";
                case "QUERY_TRANSACTIONS" -> "Step 1 · Fetching your recent transactions";
                case "QUERY_SEARCH"       -> "Step 1 · Searching transactions for " + intent.beneficiary();
                default                   -> "Step 1 · Classified as: " + intent.action();
            };
        }
        if (!intent.isTransactional() || intent.amount() <= 0) {
            return "Step 1 · Classified as: " + intent.action();
        }
        String channel = "UNKNOWN".equalsIgnoreCase(intent.channel()) ? "pending" : intent.channel();
        return switch (intent.action()) {
            case "TRANSFER" -> String.format("Step 1 · Transfer ₹%.0f to %s (%s)", intent.amount(), intent.beneficiary(), channel);
            case "RECEIVE"  -> String.format("Step 1 · Receive ₹%.0f (%s)", intent.amount(), channel);
            case "MANDATE"  -> "Step 1 · Mandate switch for " + intent.beneficiary();
            default -> "Step 1 · " + intent.action();
        };
    }
}

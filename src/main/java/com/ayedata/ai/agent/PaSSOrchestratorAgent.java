package com.ayedata.ai.agent;

import com.ayedata.ai.tools.LedgerTools;
import com.ayedata.config.MongoChatMemoryStore;
import com.ayedata.fraud.FraudAction;
import com.ayedata.fraud.FraudAgentOrchestrator;
import com.ayedata.fraud.FraudAnalysisResult;
import com.ayedata.init.UserProfileInitializer;
import com.ayedata.service.AccountBalanceService;
import com.ayedata.service.TemporalMemoryService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;

/**
 * PaSS Orchestrator Agent: Supervisor backed by MongoDB chat memory,
 * temporal memory recall, and RAG-enriched context.
 *
 * Multi-stage orchestration:
 *   Step 1: LangChain4j IntentClassifier — lightweight LLM call to classify intent + extract params
 *   Step 2: Fraud Detection — LangChain4j Fraud Agent analyses transactional intents (skipped for queries)
 *   Step 3: Execute LangChain4j @Tool methods directly based on extracted params
 *   Step 4: LangChain4j FormattingSupervisor — stream the formatted result to the user
 *
 * @see ContextEnricher  — builds enriched intent with RAG + temporal context
 * @see MongoChatMemoryStore — per-session 10-message window in MongoDB
 */
@Component
public class PaSSOrchestratorAgent {
    private static final Logger log = LoggerFactory.getLogger(PaSSOrchestratorAgent.class);

    private final LedgerTools ledgerTools;
    private final FraudAgentOrchestrator fraudAgentOrchestrator;
    private final OllamaChatModel chatLanguageModel;
    private final OllamaStreamingChatModel streamingChatModel;
    private final MongoChatMemoryStore chatMemoryStore;
    private final ContextEnricher contextEnricher;
    private final TemporalMemoryService temporalMemoryService;
    private final AccountBalanceService accountBalanceService;

    private Supervisor supervisor;
    private StreamingSupervisor streamingSupervisor;
    private FormattingSupervisor formattingSupervisor;
    private DeterministicToolExecutor deterministicExecutor;
    private boolean toolsAvailable = false;

    /** LangChain4j ToolExecutor registry — maps tool method name → executor. */
    private final Map<String, ToolExecutor> toolExecutors = new LinkedHashMap<>();

    /**
     * Bounded executor for temporal archiving. Limits concurrent fire-and-forget
     * embedding calls to prevent thread accumulation when Voyage AI is slow.
     * Virtual threads are cheap but the semaphore caps in-flight archive tasks.
     */
    private static final int MAX_CONCURRENT_ARCHIVES = 20;
    private final Semaphore archiveSemaphore = new Semaphore(MAX_CONCURRENT_ARCHIVES);
    private final ExecutorService archiveExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public PaSSOrchestratorAgent(LedgerTools ledgerTools,
                                 FraudAgentOrchestrator fraudAgentOrchestrator,
                                 OllamaChatModel chatLanguageModel,
                                 OllamaStreamingChatModel streamingChatModel,
                                 MongoChatMemoryStore chatMemoryStore,
                                 ContextEnricher contextEnricher,
                                 TemporalMemoryService temporalMemoryService,
                                 AccountBalanceService accountBalanceService) {
        this.ledgerTools = ledgerTools;
        this.fraudAgentOrchestrator = fraudAgentOrchestrator;
        this.chatLanguageModel = chatLanguageModel;
        this.streamingChatModel = streamingChatModel;
        this.chatMemoryStore = chatMemoryStore;
        this.contextEnricher = contextEnricher;
        this.temporalMemoryService = temporalMemoryService;
        this.accountBalanceService = accountBalanceService;
    }

    private static final String SYSTEM_PROMPT = """
            You are PaSS, an Indian banking payment assistant.
            You handle: UPI, NEFT, RTGS, IMPS, and cheque payments.

            CRITICAL TOOL RULES:
            1. For send/pay/transfer requests with an amount → ALWAYS call transferFunds immediately.
               Pass the beneficiary name exactly as the user gave it.
               If the user specified a channel, pass it. Otherwise omit it (auto-selects).
            2. For receive/add/deposit/top-up/credit requests with an amount → ALWAYS call receiveFunds immediately.
            3. For mandate/routing switches without money → call switchMandate.
            4. On CHANNEL_MISMATCH response → re-call the tool with the suggested channel automatically. Do NOT ask the user.
            5. Never claim a transfer succeeded unless the tool returned SUCCESS.
            6. If a tool returns TRANSFER_BLOCKED or RECEIVE_BLOCKED, relay the message to the user.

            GENERAL RULES:
            7. Use [RELEVANT KNOWLEDGE] as primary source for payment channel info.
            8. Use [YOUR ACCOUNT] to answer balance inquiries without calling any tool.
            9. Match beneficiary names to [REGISTERED USERS] for P2P transfers.
            10. Reply concisely in 2-4 sentences. Plain text only.
            11. If required details are missing (no amount or no beneficiary), ask ONE clarifying question instead of calling a tool.
            """;

    interface Supervisor {
        @SystemMessage(SYSTEM_PROMPT)
        String orchestrate(@MemoryId String sessionId, @UserMessage String userIntent);
    }

    interface StreamingSupervisor {
        @SystemMessage(SYSTEM_PROMPT)
        TokenStream orchestrate(@MemoryId String sessionId, @UserMessage String userIntent);
    }

    // ── Two-fold orchestration: Step 1 classifier, Step 3 formatter ──

    private static final String CLASSIFIER_PROMPT = """
            Classify the payment request. Reply with EXACTLY these five lines, nothing else:
            ACTION: TRANSFER or RECEIVE or MANDATE or QUERY_BALANCE or QUERY_TRANSACTIONS or QUERY_SEARCH or QUERY
            BENEFICIARY: person name or none
            AMOUNT: number or 0
            CHANNEL: channel name or auto
            CONFIDENCE: HIGH or MEDIUM or LOW

            Rules:
            - When a person's name appears in a query, use QUERY_SEARCH with BENEFICIARY set to that person.
            - Use QUERY_TRANSACTIONS for listing/filtering transactions without a specific person.
            - Use QUERY_BALANCE only for balance inquiries with no person mentioned.
            - CONFIDENCE: HIGH when all fields are unambiguous; MEDIUM when one is inferred; LOW when vague.

            Examples:
            - "what is my balance" → ACTION: QUERY_BALANCE, CONFIDENCE: HIGH
            - "show my UPI debits" → ACTION: QUERY_TRANSACTIONS, CONFIDENCE: HIGH
            - "how much I owe to Priya" → ACTION: QUERY_SEARCH, BENEFICIARY: Priya, CONFIDENCE: HIGH
            - "pay 5000 to Ramesh via UPI" → ACTION: TRANSFER, AMOUNT: 5000, BENEFICIARY: Ramesh, CHANNEL: UPI, CONFIDENCE: HIGH
            - "receive 10000" → ACTION: RECEIVE, AMOUNT: 10000, CONFIDENCE: HIGH
            - "send money to Ramesh" → ACTION: TRANSFER, BENEFICIARY: Ramesh, AMOUNT: 0, CONFIDENCE: LOW
            """;

    private static final String FORMATTER_PROMPT = """
            You are PaSS, an Indian banking payment assistant.
            The [TOOL RESULT] section shows the outcome of a payment tool that was already executed.
            Summarize the result to the user in 2-3 plain text sentences.
            If SUCCESS, confirm the amount, beneficiary, channel, and reference number.
            If BLOCKED, explain what went wrong concisely.
            For queries without [TOOL RESULT], answer using [RELEVANT KNOWLEDGE].
            """;

    interface FormattingSupervisor {
        @SystemMessage(FORMATTER_PROMPT)
        TokenStream format(@UserMessage String contextWithResult);
    }

    record ParsedIntent(String action, String beneficiary, double amount, String channel, String confidence) {
        boolean isTransfer()       { return "TRANSFER".equals(action); }
        boolean isReceive()        { return "RECEIVE".equals(action); }
        boolean isMandate()        { return "MANDATE".equals(action); }
        boolean isQueryBalance()   { return "QUERY_BALANCE".equals(action); }
        boolean isQueryTxns()      { return "QUERY_TRANSACTIONS".equals(action); }
        boolean isQuerySearch()    { return "QUERY_SEARCH".equals(action); }
        boolean isTransactional()  { return isTransfer() || isReceive() || isMandate(); }
        boolean isQueryTool()      { return isQueryBalance() || isQueryTxns() || isQuerySearch(); }
        boolean isHighConfidence() { return "HIGH".equalsIgnoreCase(confidence); }
    }

    /** Wraps classifier raw text output together with Step 1 token usage. */
    record ClassificationResult(String raw, int inputTokens, int outputTokens) {
        int totalTokens() { return inputTokens + outputTokens; }
    }

    @PostConstruct
    public void init() {
        log.info("🚀 Initializing Agentic Orchestrator with MongoDB memory + RAG + temporal recall...");

        try {
            // Synchronous supervisor (for /orchestrate endpoint)
            try {
                this.supervisor = AiServices.builder(Supervisor.class)
                        .chatModel(chatLanguageModel)
                        .chatMemoryProvider(memId -> MessageWindowChatMemory.builder()
                                .id(memId)
                                .maxMessages(4)
                                .chatMemoryStore(chatMemoryStore)
                                .build())
                        .tools(ledgerTools)
                        .build();
                log.info("✅ Supervisor Agent initialized with tool support and MongoDB memory.");
                toolsAvailable = true;
            } catch (Exception toolsException) {
                log.error("❌ CRITICAL: Tool support initialization failed. Transaction tools will NOT be available. Reason: {}", toolsException.getMessage());
                log.error("❌ This means the LLM cannot execute payment transactions. Check MongoDB connectivity and service dependencies.");
                this.supervisor = AiServices.builder(Supervisor.class)
                        .chatModel(chatLanguageModel)
                        .chatMemoryProvider(memId -> MessageWindowChatMemory.builder()
                                .id(memId)
                                .maxMessages(4)
                                .chatMemoryStore(chatMemoryStore)
                                .build())
                        .build();
                log.warn("✅ Supervisor Agent initialized in tool-less mode with MongoDB memory. TRANSACTION TOOLS DISABLED.");
            }

            // Streaming supervisor (for /orchestrate-stream endpoint)
            try {
                this.streamingSupervisor = AiServices.builder(StreamingSupervisor.class)
                        .streamingChatModel(streamingChatModel)
                        .chatMemoryProvider(memId -> MessageWindowChatMemory.builder()
                                .id(memId)
                                .maxMessages(4)
                                .chatMemoryStore(chatMemoryStore)
                                .build())
                        .tools(ledgerTools)
                        .build();
                log.info("✅ Streaming Supervisor Agent initialized with tool support.");
            } catch (Exception toolsException) {
                log.error("❌ CRITICAL: Streaming tool support initialization failed. Transaction tools will NOT be available for streaming. Reason: {}", toolsException.getMessage());
                log.error("❌ This means the LLM cannot execute payment transactions in streaming mode. Check MongoDB connectivity and service dependencies.");
                this.streamingSupervisor = AiServices.builder(StreamingSupervisor.class)
                        .streamingChatModel(streamingChatModel)
                        .chatMemoryProvider(memId -> MessageWindowChatMemory.builder()
                                .id(memId)
                                .maxMessages(4)
                                .chatMemoryStore(chatMemoryStore)
                                .build())
                        .build();
                log.warn("✅ Streaming Supervisor Agent initialized in tool-less mode.");
            }

            // Formatting supervisor (Step 3 of two-fold) — streaming, no tools, NO memory.
            // This agent only summarises tool results in a single turn; it must not
            // share the Supervisor's chat memory (which would cause message interleaving).
            this.formattingSupervisor = AiServices.builder(FormattingSupervisor.class)
                    .streamingChatModel(streamingChatModel)
                    .build();
            log.info("✅ Formatting Supervisor initialized (two-fold step 3, stateless).");

            // Register LangChain4j ToolExecutors for every @Tool method in LedgerTools
            for (Method method : ledgerTools.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    toolExecutors.put(method.getName(), new DefaultToolExecutor(ledgerTools, method));
                    log.debug("Registered LangChain4j ToolExecutor: {}", method.getName());
                }
            }
            log.info("✅ {} LangChain4j ToolExecutors registered for programmatic invocation.", toolExecutors.size());

            // Deterministic fallback executor — used when classifier confidence is not HIGH
            this.deterministicExecutor = new DeterministicToolExecutor(ledgerTools, toolExecutors);
            log.info("✅ DeterministicToolExecutor initialized as confidence fallback.");

        } catch (Exception e) {
            log.error("Failed to initialize Supervisor", e);
            throw new RuntimeException("Supervisor initialization failed", e);
        }

        log.info("✅ Supervisor Agent ready. Two-fold orchestration ✓  MongoDB memory ✓  RAG ✓");
    }

    /**
     * Check if transaction tools are available for execution.
     * @return true if tools are initialized and can execute transactions
     */
    public boolean areToolsAvailable() {
        return toolsAvailable;
    }

    private static final String EMBEDDING_UNAVAILABLE_MSG =
            "I'm sorry, but the AI embedding service (Voyage AI) is currently unreachable. "
            + "Without it I cannot safely process your request. Please try again in a few moments.";

    /**
     * Two-fold synchronous orchestration:
     * Step 1: classify intent via LangChain4j, Step 2: execute tool, Step 3: format via LLM.
     */
    public String orchestrateSwitch(String sessionId, String userId, String userIntent) {
        log.info("📨 Session {}: Two-fold sync orchestration (user={})...", sessionId, userId);

        if (!toolsAvailable) {
            log.warn("⚠️ Session {}: Tools not available, cannot execute transactions", sessionId);
            return "I apologize, but the transaction system is currently unavailable. Please try again later or contact support if this issue persists.";
        }

        // Pre-flight: verify Voyage AI embedding service is reachable
        if (!temporalMemoryService.checkEmbeddingHealth()) {
            log.error("❌ Session {}: Embedding service unreachable — aborting orchestration", sessionId);
            return EMBEDDING_UNAVAILABLE_MSG;
        }

        ledgerTools.registerSession(sessionId, userId);
        try {
            // Step 1: Classify
            String classifierCtx = buildClassifierContext(userId, userIntent);
            ParsedIntent intent;
            try {
                ClassificationResult cr = classifyWithRetry(sessionId, classifierCtx);
                log.info("Session {}: Classifier → {} (step1 tokens: in={} out={})",
                        sessionId, cr.raw().replace("\n", " | "), cr.inputTokens(), cr.outputTokens());
                intent = parseClassification(cr.raw());
            } catch (Exception e) {
                log.warn("Session {}: Classification failed after retries, falling back to supervisor", sessionId, e);
                String enrichedIntent = contextEnricher.buildEnrichedIntent(sessionId, userIntent, userId);
                return supervisor.orchestrate(sessionId, enrichedIntent);
            }

            // Step 2: Fraud detection (transactional intents only)
            if (intent.isTransactional()) {
                log.info("Session {}: Step 2 — fraud analysis (amount=₹{} beneficiary={} channel={})",
                        sessionId, intent.amount(), intent.beneficiary(), intent.channel());
                FraudAnalysisResult fraudResult = fraudAgentOrchestrator.analyze(
                        sessionId, userId, userIntent, intent.amount(), intent.beneficiary(), intent.channel());
                ledgerTools.cacheFraudResult(sessionId, fraudResult);
                log.info("Session {}: Fraud analysis: score={} action={} signals={}",
                        sessionId, fraudResult.riskScore(), fraudResult.action(), fraudResult.fraudSignals());

                if (fraudResult.action() == FraudAction.BLOCK) {
                    return String.format(
                            "TRANSACTION_BLOCKED: Fraud detection blocked this transaction. Risk score: %.2f, Signals: %s",
                            fraudResult.riskScore(), String.join(", ", fraudResult.fraudSignals()));
                }
            }

            // Step 3 (or 2): Execute and format.
            // HIGH confidence → LLM-led tool orchestration (Supervisor decides tools).
            // MEDIUM/LOW confidence → deterministic Java execution for reliability.
            String enrichedIntent = contextEnricher.buildEnrichedIntent(sessionId, userIntent, userId);
            String reply;

            if (intent.isHighConfidence()) {
                log.info("Session {}: Step 2+3 — LLM-led tool execution (confidence=HIGH, action={}, enriched={}chars)",
                        sessionId, intent.action(), enrichedIntent.length());
                reply = supervisor.orchestrate(sessionId, enrichedIntent);
                log.debug("✅ Session {}: LLM-led orchestration completed", sessionId);
            } else if (intent.isTransactional() || intent.isQueryTool()) {
                log.info("Session {}: Step 2 — deterministic fallback (confidence={}, action={})",
                        sessionId, intent.confidence(), intent.action());
                String toolResult = deterministicExecutor.executeToolDirectly(sessionId, intent, userIntent);
                if (toolResult != null) {
                    reply = toolResult;
                    log.info("Session {}: Step 2 — deterministic tool returned result directly", sessionId);
                } else {
                    log.info("Session {}: Step 2 — deterministic returned null, falling through to LLM", sessionId);
                    reply = supervisor.orchestrate(sessionId, enrichedIntent);
                }
            } else {
                log.info("Session {}: Step 2+3 — general query, LLM orchestration (confidence={})",
                        sessionId, intent.confidence());
                reply = supervisor.orchestrate(sessionId, enrichedIntent);
            }

            submitTemporalArchive(sessionId, userIntent, reply);

            return reply;
        } catch (Exception e) {
            log.error("❌ Session {}: Orchestration failed", sessionId, e);
            throw new RuntimeException("Orchestration failed: " + e.getMessage(), e);
        } finally {
            ledgerTools.unregisterSession(sessionId);
        }
    }

    /**
     * Two-fold streaming orchestration with stage notifications:
     * <ol>
     *   <li>LLM call 1 (LangChain4j IntentClassifier): classify intent + extract params</li>
     *   <li>Execute LangChain4j @Tool methods directly based on extracted params</li>
     *   <li>LLM call 2 (LangChain4j FormattingSupervisor): stream formatted response</li>
     * </ol>
     * The {@code stageCallback} receives (stageName, detailJson) at each step so the
     * controller can push SSE stage events to the UI in real time.
     * Falls back to the original single-pass streaming supervisor if classification fails.
     */
    public TokenStream orchestrateSwitchStreaming(String sessionId, String userId, String userIntent,
                                                  BiConsumer<String, Map<String, Object>> stageCallback) {
        log.info("📨 Session {}: Two-fold orchestration start (user={})", sessionId, userId);

        if (!toolsAvailable) {
            log.warn("⚠️ Session {}: Tools not available for streaming", sessionId);
            throw new IllegalStateException("Transaction tools are currently unavailable. Please try again later.");
        }

        // Pre-flight: verify Voyage AI embedding service is reachable
        if (!temporalMemoryService.checkEmbeddingHealth()) {
            log.error("❌ Session {}: Embedding service unreachable — aborting streaming orchestration", sessionId);
            return new SyntheticTokenStream(EMBEDDING_UNAVAILABLE_MSG);
        }

        ledgerTools.registerSession(sessionId, userId);

        // ── Step 1: Intent classification via LangChain4j (lightweight LLM call) ──
        stageCallback.accept("classifying", Map.of(
                "message", "Step 1 · Classifying your intent...",
                "step", 1, "totalSteps", 3));

        String classifierCtx = buildClassifierContext(userId, userIntent);
        ParsedIntent intent;
        boolean needsFraud;
        int totalSteps;
        int stepOffset;
        try {
            log.info("Session {}: Step 1 — classifying intent ({} chars)...", sessionId, classifierCtx.length());
            ClassificationResult cr = classifyWithRetry(sessionId, classifierCtx);
            log.info("Session {}: Classifier output: {} (step1 tokens: in={} out={})",
                    sessionId, cr.raw().replace("\n", " | "), cr.inputTokens(), cr.outputTokens());
            intent = parseClassification(cr.raw());
            log.info("Session {}: Parsed → action={} beneficiary={} amount={} channel={} confidence={}",
                    sessionId, intent.action(), intent.beneficiary(), intent.amount(), intent.channel(), intent.confidence());

            // Compute total steps based on path: transactional intents get a fraud stage
            needsFraud = intent.isTransactional();
            boolean willBeDeterministic = !intent.isHighConfidence() && (intent.isTransactional() || intent.isQueryTool());
            totalSteps = willBeDeterministic ? (needsFraud ? 4 : 3) : (needsFraud ? 3 : 2);
            stepOffset = needsFraud ? 1 : 0; // shifts execute/format steps when fraud is present

            Map<String, Object> classifiedData = new java.util.LinkedHashMap<>();
            classifiedData.put("message", formatClassifiedMessage(intent));
            classifiedData.put("step", 1);
            classifiedData.put("totalSteps", totalSteps);
            classifiedData.put("action", intent.action());
            classifiedData.put("amount", intent.amount());
            classifiedData.put("beneficiary", intent.beneficiary());
            classifiedData.put("channel", intent.channel());
            classifiedData.put("confidence", intent.confidence());
            classifiedData.put("step1InputTokens", cr.inputTokens());
            classifiedData.put("step1OutputTokens", cr.outputTokens());
            stageCallback.accept("classified", classifiedData);

        } catch (Exception e) {
            log.warn("Session {}: Classification failed after retries, falling back to direct streaming", sessionId, e);
            stageCallback.accept("fallback", Map.of(
                    "message", "Intent classification unavailable — using direct orchestration.",
                    "step", 1, "totalSteps", 1));
            String enrichedIntent = contextEnricher.buildEnrichedIntent(sessionId, userIntent, userId);
            return streamingSupervisor.orchestrate(sessionId, enrichedIntent);
        }

        // ── Step 2: Fraud detection (transactional intents only) ──
        if (needsFraud) {
            stageCallback.accept("fraud_analyzing", Map.of(
                    "message", "Step 2 · Analyzing fraud risk...",
                    "step", 2, "totalSteps", totalSteps));

            log.info("Session {}: Step 2 — fraud analysis (amount=₹{} beneficiary={} channel={})",
                    sessionId, intent.amount(), intent.beneficiary(), intent.channel());
            long fraudStart = System.currentTimeMillis();

            FraudAnalysisResult fraudResult = fraudAgentOrchestrator.analyze(
                    sessionId, userId, userIntent, intent.amount(), intent.beneficiary(), intent.channel());

            long fraudElapsedMs = System.currentTimeMillis() - fraudStart;
            log.info("Session {}: Fraud analysis complete ({}ms): score={} action={} signals={}",
                    sessionId, fraudElapsedMs, fraudResult.riskScore(), fraudResult.action(), fraudResult.fraudSignals());

            // Cache result so LedgerTools skips duplicate analysis during tool execution
            ledgerTools.cacheFraudResult(sessionId, fraudResult);

            Map<String, Object> fraudData = new java.util.LinkedHashMap<>();
            fraudData.put("message", String.format("Step 2 · Fraud Score: %.2f (%s)",
                    fraudResult.riskScore(), fraudResult.action()));
            fraudData.put("step", 2);
            fraudData.put("totalSteps", totalSteps);
            fraudData.put("riskScore", fraudResult.riskScore());
            fraudData.put("behavioralScore", fraudResult.behavioralScore());
            fraudData.put("action", fraudResult.action().toString());
            fraudData.put("signals", fraudResult.fraudSignals());
            fraudData.put("fraudElapsedMs", fraudElapsedMs);

            if (fraudResult.action() == FraudAction.BLOCK) {
                fraudData.put("blocked", true);
                stageCallback.accept("fraud_analyzed", fraudData);
                return new SyntheticTokenStream(String.format(
                        "TRANSACTION_BLOCKED: Fraud detection blocked this transaction. Risk score: %.2f, Signals: %s",
                        fraudResult.riskScore(), String.join(", ", fraudResult.fraudSignals())));
            }

            stageCallback.accept("fraud_analyzed", fraudData);
        }

        // ── Step 3 (or 2): Execute and stream response ──
        // HIGH confidence → LLM-led tool orchestration (StreamingSupervisor decides tools).
        // MEDIUM/LOW confidence → deterministic Java execution for reliability.
        int execStep = 2 + stepOffset;
        int fmtStep  = 3 + stepOffset;
        String enrichedIntent = contextEnricher.buildEnrichedIntent(sessionId, userIntent, userId);

        if (!intent.isHighConfidence() && (intent.isTransactional() || intent.isQueryTool())) {
            // Deterministic fallback
            log.info("Session {}: Step {} — deterministic fallback (confidence={}, action={})",
                    sessionId, execStep, intent.confidence(), intent.action());
            stageCallback.accept("executing", Map.of(
                    "message", String.format("Step %d · Deterministic execution (confidence: %s)...", execStep, intent.confidence()),
                    "step", execStep, "totalSteps", totalSteps,
                    "action", intent.action(),
                    "confidence", intent.confidence()));
            long step2Start = System.currentTimeMillis();
            try {
                String toolResult = deterministicExecutor.executeToolDirectly(sessionId, intent, userIntent);
                long step2ElapsedMs = System.currentTimeMillis() - step2Start;
                if (toolResult != null) {
                    log.info("Session {}: Deterministic tool result: {} ({}ms)", sessionId, toolResult, step2ElapsedMs);
                    boolean success = toolResult.startsWith("SUCCESS");
                    String extractedChannel = DeterministicToolExecutor.extractChannelFromResult(toolResult);
                    Map<String, Object> execData = new java.util.LinkedHashMap<>();
                    execData.put("message", success
                            ? String.format("Step %d · %s %s complete.", execStep,
                                    intent.action(), extractedChannel != null ? "via " + extractedChannel : "")
                            : String.format("Step %d · Tool returned: %s", execStep,
                                    toolResult.substring(0, Math.min(toolResult.length(), 80))));
                    execData.put("step", execStep);
                    execData.put("totalSteps", totalSteps);
                    execData.put("success", success);
                    execData.put("step2ElapsedMs", step2ElapsedMs);
                    if (extractedChannel != null) execData.put("channel", extractedChannel);
                    stageCallback.accept("executed", execData);

                    stageCallback.accept("formatting", Map.of(
                            "message", String.format("Step %d · Streaming result...", fmtStep),
                            "step", fmtStep, "totalSteps", totalSteps));
                    return new SyntheticTokenStream(toolResult);
                }
                // Null result — fall through to LLM
                log.info("Session {}: Deterministic returned null ({}ms), falling through to LLM", sessionId, step2ElapsedMs);
            } catch (Exception e) {
                long step2ElapsedMs = System.currentTimeMillis() - step2Start;
                log.error("Session {}: Deterministic execution failed ({}ms), falling through to LLM",
                        sessionId, step2ElapsedMs, e);
            }
        }

        // LLM-led path (HIGH confidence, general queries, or deterministic fallback failure)
        stageCallback.accept("executing", Map.of(
                "message", String.format("Step %d · AI orchestrating tool execution...", execStep),
                "step", execStep, "totalSteps", totalSteps,
                "action", intent.action(),
                "confidence", intent.confidence()));

        log.info("Session {}: Step {} — LLM-led tool execution with streaming (confidence={}, action={}, enriched={}chars)",
                sessionId, execStep, intent.confidence(), intent.action(), enrichedIntent.length());
        return streamingSupervisor.orchestrate(sessionId, enrichedIntent);
    }

    /** Clean up session→userId mapping after streaming completes or errors. */
    public void cleanupStreamingSession(String sessionId) {
        ledgerTools.unregisterSession(sessionId);
    }

    public TemporalMemoryService getTemporalMemoryService() {
        return temporalMemoryService;
    }

    /**
     * Submit a temporal archival task on the bounded virtual thread executor.
     * If the concurrency cap ({@value MAX_CONCURRENT_ARCHIVES}) is reached,
     * the task is silently dropped to prevent thread accumulation.
     */
    public void submitTemporalArchive(String sessionId, String userIntent, String reply) {
        if (!archiveSemaphore.tryAcquire()) {
            log.warn("Session {}: Temporal archive skipped — concurrency cap ({}) reached",
                    sessionId, MAX_CONCURRENT_ARCHIVES);
            return;
        }
        archiveExecutor.submit(() -> {
            try {
                temporalMemoryService.archiveTurn(sessionId, userIntent, reply);
            } finally {
                archiveSemaphore.release();
            }
        });
    }

    // ── Two-fold helpers ──

    /** Max retries for Step 1 intent classification (Ollama can be flaky on CPU). */
    private static final int CLASSIFIER_MAX_RETRIES = 1;
    /** Backoff between classification retries (ms). */
    private static final long CLASSIFIER_RETRY_DELAY_MS = 2000;

    /**
     * Classify with retry — attempts {@code CLASSIFIER_MAX_RETRIES + 1} calls
     * to the LLM before giving up. Calls the model directly (bypassing the
     * AiServices proxy) so we can capture Step 1 token usage.
     */
    private ClassificationResult classifyWithRetry(String sessionId, String classifierCtx) {
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
                                dev.langchain4j.data.message.SystemMessage.from(CLASSIFIER_PROMPT),
                                dev.langchain4j.data.message.UserMessage.from(classifierCtx)))
                        .build();
                ChatResponse resp = chatLanguageModel.chat(req);
                TokenUsage tu = resp.tokenUsage();
                int in  = (tu != null && tu.inputTokenCount()  != null) ? tu.inputTokenCount()  : 0;
                int out = (tu != null && tu.outputTokenCount() != null) ? tu.outputTokenCount() : 0;
                return new ClassificationResult(resp.aiMessage().text(), in, out);
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
     * Build a compact context for the intent classifier (Step 1).
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

        // Balance — helps classify "can I afford..." / "how much do I have"
        try {
            double balance = accountBalanceService.getCurrentBalance(userId);
            sb.append("\nBalance: ₹").append(String.format("%.0f", balance));
        } catch (Exception ignored) { }

        // Last 3 transactions — helps classify "did I pay X" / "show history"
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
     * Parse the classifier's structured text output into a {@link ParsedIntent}.
     * Tolerant of extra whitespace and minor formatting variations.
     */
    private ParsedIntent parseClassification(String raw) {
        String action = "QUERY";
        String beneficiary = "none";
        double amount = 0;
        String channel = "auto";
        String confidence = "HIGH";

        // Normalize: models sometimes emit comma-separated key: value pairs
        // on a single line instead of separate lines.
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
                channel = line.substring(8).trim();
            } else if (line.toUpperCase().startsWith("CONFIDENCE:")) {
                confidence = line.substring(11).trim().toUpperCase();
            }
        }

        return new ParsedIntent(action, beneficiary, amount, channel, confidence);
    }

    /** Format a human-readable stage message from a classified intent. */
    private static String formatClassifiedMessage(ParsedIntent intent) {
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
        String channel = "auto".equalsIgnoreCase(intent.channel()) ? "auto-select" : intent.channel();
        return switch (intent.action()) {
            case "TRANSFER" -> String.format("Step 1 · Transfer ₹%.0f to %s (%s)", intent.amount(), intent.beneficiary(), channel);
            case "RECEIVE"  -> String.format("Step 1 · Receive ₹%.0f (%s)", intent.amount(), channel);
            case "MANDATE"  -> "Step 1 · Mandate switch for " + intent.beneficiary();
            default -> "Step 1 · " + intent.action();
        };
    }
}

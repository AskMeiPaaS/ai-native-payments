package com.ayedata.ai.agent;

import com.ayedata.ai.tools.LedgerTools;
import com.ayedata.config.MongoChatMemoryStore;
import com.ayedata.service.TemporalMemoryService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PaSS Orchestrator Agent: Supervisor backed by MongoDB chat memory,
 * temporal memory recall, and RAG-enriched context.
 *
 * @see ContextEnricher  — builds enriched intent with RAG + temporal context
 * @see MongoChatMemoryStore — per-session 10-message window in MongoDB
 */
@Component
public class PaSSOrchestratorAgent {
    private static final Logger log = LoggerFactory.getLogger(PaSSOrchestratorAgent.class);

    private final LedgerTools ledgerTools;
    private final OllamaChatModel chatLanguageModel;
    private final OllamaStreamingChatModel streamingChatModel;
    private final MongoChatMemoryStore chatMemoryStore;
    private final ContextEnricher contextEnricher;
    private final TemporalMemoryService temporalMemoryService;

    private Supervisor supervisor;
    private StreamingSupervisor streamingSupervisor;

    public PaSSOrchestratorAgent(LedgerTools ledgerTools,
                                 OllamaChatModel chatLanguageModel,
                                 OllamaStreamingChatModel streamingChatModel,
                                 MongoChatMemoryStore chatMemoryStore,
                                 ContextEnricher contextEnricher,
                                 TemporalMemoryService temporalMemoryService) {
        this.ledgerTools = ledgerTools;
        this.chatLanguageModel = chatLanguageModel;
        this.streamingChatModel = streamingChatModel;
        this.chatMemoryStore = chatMemoryStore;
        this.contextEnricher = contextEnricher;
        this.temporalMemoryService = temporalMemoryService;
    }

    private static final String SYSTEM_PROMPT = """
            You are PaSS, an Indian banking payment assistant.
            You handle: UPI, NEFT, RTGS, IMPS, and cheque payments.

            Rules:
            1. Use [RELEVANT KNOWLEDGE] as your primary source. Answer from it first.
            2. Use [CONVERSATION HISTORY] for continuity with prior turns.
            3. Answer the [CURRENT REQUEST] concisely in 2-4 sentences.
            4. For send/pay/transfer/outbound requests with an amount, ALWAYS call the transferFunds tool immediately. \
               Pass the beneficiary exactly as given by the user — this can be a person name, bank account number, UPI ID, or merchant ID. \
               If the user explicitly states a channel (e.g. "via UPI", "through NEFT"), pass it to the tool exactly as given. \
               If the user does NOT specify a channel, call the tool without a channel — the tool will auto-select the optimal channel. \
               NEVER reason about whether a channel or amount is valid — always call the tool and rely on CHANNEL_MISMATCH responses.
            5. For mandate switches without money movement, call the switchMandate tool.
            6. For receive/add/deposit/top-up/credit/inbound fund requests with an amount, ALWAYS call the receiveFunds tool immediately. \
               If the user explicitly states a channel, pass it to the tool exactly as given. \
               If the user does NOT specify a channel, call the tool without a channel — the tool will auto-select the optimal channel. \
               NEVER reject or warn about channel-amount compatibility yourself — always call the tool and rely on CHANNEL_MISMATCH responses.
            7. Never claim a transfer or receipt succeeded unless a tool returned SUCCESS.
            8. If a tool returns TRANSFER_BLOCKED or RECEIVE_BLOCKED, relay the message clearly and ask the user for any missing details.
            9. Reply in plain text only. Never echo the knowledge sections back.
            10. If [APPROVED CHANNELS] is absent (RAG unavailable), ask the user to retry in a moment.
            11. [YOUR IDENTITY] tells you which user you are serving. [REGISTERED USERS] lists other users in the system. \
                When the user mentions a name that matches a registered user, use that name as the beneficiary for transferFunds.
            12. [YOUR ACCOUNT] shows the user's current balance and account number. Use it to answer balance inquiries directly \
                without calling any tool.
            13. CONFIDENCE ASSESSMENT — Before every response, silently assess how completely and unambiguously you understand the request: \
                HIGH: all required details are present and clear (amount + identifiable beneficiary for transfers; amount for receives). \
                MEDIUM: required details are present but one element has minor ambiguity that context resolves. \
                LOW: a required detail is missing (no amount, no recognisable beneficiary) OR the intent itself is unclear. \
                Act immediately on HIGH and MEDIUM confidence. For LOW confidence, do NOT call any tool — ask ONE focused clarifying question instead.
            14. CLARIFICATION — When confidence is LOW, ask the single most important missing detail only. Examples: \
                missing amount → "How much would you like to send to Priya?"; \
                unrecognised beneficiary → "Who should receive ₹500? Please share their name, UPI ID, or account number."; \
                ambiguous intent → "Did you mean to send ₹20 to Priya, or did you want to check something else?"
            15. RESPONSE WEIGHT — End every response on a new line with your confidence rating in this exact format: \
                [Confidence: HIGH], [Confidence: MEDIUM], or [Confidence: LOW]. \
                For pure clarification questions use [Confidence: LOW]. For successful tool outcomes use [Confidence: HIGH].
            """;

    interface Supervisor {
        @SystemMessage(SYSTEM_PROMPT)
        String orchestrate(@MemoryId String sessionId, @UserMessage String userIntent);
    }

    interface StreamingSupervisor {
        @SystemMessage(SYSTEM_PROMPT)
        TokenStream orchestrate(@MemoryId String sessionId, @UserMessage String userIntent);
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
                                .maxMessages(6)
                                .chatMemoryStore(chatMemoryStore)
                                .build())
                        .tools(ledgerTools)
                        .build();
                log.info("✅ Supervisor Agent initialized with tool support and MongoDB memory.");
            } catch (Exception toolsException) {
                log.warn("⚠️  Tool support initialization failed. Falling back to tool-less mode. Reason: {}", toolsException.getMessage());
                this.supervisor = AiServices.builder(Supervisor.class)
                        .chatModel(chatLanguageModel)
                        .chatMemoryProvider(memId -> MessageWindowChatMemory.builder()
                                .id(memId)
                                .maxMessages(6)
                                .chatMemoryStore(chatMemoryStore)
                                .build())
                        .build();
                log.info("✅ Supervisor Agent initialized in tool-less mode with MongoDB memory.");
            }

            // Streaming supervisor (for /orchestrate-stream endpoint)
            try {
                this.streamingSupervisor = AiServices.builder(StreamingSupervisor.class)
                        .streamingChatModel(streamingChatModel)
                        .chatMemoryProvider(memId -> MessageWindowChatMemory.builder()
                                .id(memId)
                                .maxMessages(6)
                                .chatMemoryStore(chatMemoryStore)
                                .build())
                        .tools(ledgerTools)
                        .build();
                log.info("✅ Streaming Supervisor Agent initialized with tool support.");
            } catch (Exception toolsException) {
                log.warn("⚠️  Streaming tool support initialization failed. Falling back to tool-less mode. Reason: {}",
                        toolsException.getMessage());
                this.streamingSupervisor = AiServices.builder(StreamingSupervisor.class)
                        .streamingChatModel(streamingChatModel)
                        .chatMemoryProvider(memId -> MessageWindowChatMemory.builder()
                                .id(memId)
                                .maxMessages(6)
                                .chatMemoryStore(chatMemoryStore)
                                .build())
                        .build();
                log.info("✅ Streaming Supervisor Agent initialized in tool-less mode.");
            }

        } catch (Exception e) {
            log.error("Failed to initialize Supervisor", e);
            throw new RuntimeException("Supervisor initialization failed", e);
        }

        log.info("✅ Supervisor Agent ready. MongoDB memory ✓  Temporal recall ✓  RAG ✓");
    }

    /**
     * Orchestrate a payment intent through the Supervisor Agent, enriched with
     * RAG context and temporal memory recall.
     */
    public String orchestrateSwitch(String sessionId, String userId, String userIntent) {
        log.info("📨 Session {}: Routing user intent to Supervisor Agent (user={})...", sessionId, userId);

        ledgerTools.registerSession(sessionId, userId);
        try {
            String enrichedIntent = contextEnricher.buildEnrichedIntent(sessionId, userIntent, userId);
            String reply = supervisor.orchestrate(sessionId, enrichedIntent);
            log.debug("✅ Session {}: Supervisor completed orchestration", sessionId);

            // Archive this turn asynchronously — never blocks the response path
            Thread.ofVirtual().name("temporal-archive-" + sessionId)
                    .start(() -> temporalMemoryService.archiveTurn(sessionId, userIntent, reply));

            return reply;
        } catch (Exception e) {
            log.error("❌ Session {}: Orchestration failed", sessionId, e);
            throw new RuntimeException("Orchestration failed: " + e.getMessage(), e);
        } finally {
            ledgerTools.unregisterSession(sessionId);
        }
    }

    /**
     * Streaming orchestration: returns a TokenStream that emits tokens in real-time.
     * Pre-LLM tool enforcement runs synchronously; the result is injected into the
     * enriched intent so the LLM only formats the confirmed outcome.
     */
    public TokenStream orchestrateSwitchStreaming(String sessionId, String userId, String userIntent) {
        log.info("📨 Session {}: Routing user intent to Streaming Supervisor (user={})...", sessionId, userId);
        ledgerTools.registerSession(sessionId, userId);

        String enrichedIntent = contextEnricher.buildEnrichedIntent(sessionId, userIntent, userId);
        return streamingSupervisor.orchestrate(sessionId, enrichedIntent);
    }

    /** Clean up session→userId mapping after streaming completes or errors. */
    public void cleanupStreamingSession(String sessionId) {
        ledgerTools.unregisterSession(sessionId);
    }

    public TemporalMemoryService getTemporalMemoryService() {
        return temporalMemoryService;
    }
}

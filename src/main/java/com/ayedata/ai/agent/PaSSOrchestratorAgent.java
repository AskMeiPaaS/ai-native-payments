package com.ayedata.ai.agent;

import com.ayedata.ai.tools.LedgerTools;
import com.ayedata.config.MongoChatMemoryStore;
import com.ayedata.service.TemporalMemoryService;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
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
            You handle: UPI, NEFT, RTGS, IMPS, cheque, and cash payments.

            Rules:
            1. Use [RELEVANT KNOWLEDGE] as your primary source. Answer from it first.
            2. Use [CONVERSATION HISTORY] for continuity with prior turns.
            3. Answer the [CURRENT REQUEST] concisely in 2-4 sentences.
            4. For send/pay/transfer/outbound requests with an amount, call the transferFunds tool. Pass the beneficiary exactly as given by the user — this can be a person name, bank account number, UPI ID, or merchant ID. The channel parameter MUST be one of the channels listed in [APPROVED CHANNELS]; never use a channel not present in that list.
            5. For mandate switches without money movement, call the switchMandate tool.
            6. For receive/add/deposit/top-up/credit/inbound fund requests with an amount, call the receiveFunds tool. The channel parameter MUST be one of the channels listed in [APPROVED CHANNELS]. Do not apply your own hardcoded thresholds; use the limits stated in [RELEVANT KNOWLEDGE].
            7. Never claim a transfer or receipt succeeded unless a tool returned SUCCESS.
            8. If a tool returns TRANSFER_BLOCKED or RECEIVE_BLOCKED, explain the reason clearly and suggest corrective action.
            9. Reply in plain text only. Never echo the knowledge sections back.
            10. If [APPROVED CHANNELS] is absent (RAG unavailable), ask the user to retry in a moment.
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
    public String orchestrateSwitch(String sessionId, String userIntent) {
        log.info("📨 Session {}: Routing user intent to Supervisor Agent...", sessionId);

        try {
            String enrichedIntent = contextEnricher.buildEnrichedIntent(sessionId, userIntent);
            String reply = supervisor.orchestrate(sessionId, enrichedIntent);
            log.debug("✅ Session {}: Supervisor completed orchestration", sessionId);

            // Archive this turn asynchronously — never blocks the response path
            Thread.ofVirtual().name("temporal-archive-" + sessionId)
                    .start(() -> temporalMemoryService.archiveTurn(sessionId, userIntent, reply));

            return reply;
        } catch (Exception e) {
            log.error("❌ Session {}: Orchestration failed", sessionId, e);
            throw new RuntimeException("Orchestration failed: " + e.getMessage(), e);
        }
    }

    /**
     * Streaming orchestration: returns a TokenStream that emits tokens in real-time.
     * The caller is responsible for subscribing to onPartialResponse / onCompleteResponse.
     */
    public TokenStream orchestrateSwitchStreaming(String sessionId, String userIntent) {
        log.info("📨 Session {}: Routing user intent to Streaming Supervisor...", sessionId);
        String enrichedIntent = contextEnricher.buildEnrichedIntent(sessionId, userIntent);
        return streamingSupervisor.orchestrate(sessionId, enrichedIntent);
    }

    public TemporalMemoryService getTemporalMemoryService() {
        return temporalMemoryService;
    }
}

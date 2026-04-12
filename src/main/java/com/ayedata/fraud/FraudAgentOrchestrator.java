package com.ayedata.fraud;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrates fraud analysis via a LangChain4j Agent.
 * The agent uses FraudTools (@Tool methods) to gather signals and compute risk,
 * then returns a structured decision following the action thresholds.
 *
 * Falls back to deterministic analysis via FraudSignalAnalyzer if the LLM
 * response cannot be parsed (safety net).
 */
@Component
public class FraudAgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(FraudAgentOrchestrator.class);

    private static final Pattern DECISION_PATTERN = Pattern.compile(
            "DECISION:\\s*(APPROVE|MONITOR|ESCALATE|BLOCK)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RISK_SCORE_PATTERN = Pattern.compile(
            "RISK_SCORE:\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIGNALS_PATTERN = Pattern.compile(
            "SIGNALS:\\s*(.+?)(?:\\n|$)", Pattern.CASE_INSENSITIVE);

    private final OllamaChatModel chatModel;
    private final FraudTools fraudTools;
    private final FraudSignalAnalyzer fraudSignalAnalyzer;
    private final FraudConfig fraudConfig;

    private FraudAgent fraudAgent;

    public FraudAgentOrchestrator(OllamaChatModel chatModel,
                                  FraudTools fraudTools,
                                  FraudSignalAnalyzer fraudSignalAnalyzer,
                                  FraudConfig fraudConfig) {
        this.chatModel = chatModel;
        this.fraudTools = fraudTools;
        this.fraudSignalAnalyzer = fraudSignalAnalyzer;
        this.fraudConfig = fraudConfig;
    }

    @PostConstruct
    public void init() {
        this.fraudAgent = AiServices.builder(FraudAgent.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memId -> MessageWindowChatMemory.builder()
                        .id(memId)
                        .maxMessages(fraudConfig.getAgentMemoryWindow())
                        .build())
                .tools(fraudTools)
                .build();
        log.info("✅ Fraud Agent initialized with LangChain4j orchestration and {} @Tool methods",
                fraudTools.getClass().getDeclaredMethods().length);
    }

    /**
     * Analyze a transaction for fraud via the LangChain4j Fraud Agent.
     * The agent orchestrates RAG retrieval → behavioral scoring → signal analysis → risk computation.
     */
    public FraudAnalysisResult analyze(String sessionId, String userId,
                                       double amount, String beneficiary, String channel) {
        return analyze(sessionId, userId, null, amount, beneficiary, channel);
    }

    public FraudAnalysisResult analyze(String sessionId, String userId, String userIntent,
                                       double amount, String beneficiary, String channel) {
        String intent = (userIntent != null && !userIntent.isBlank()) ? userIntent :
                "Transfer ₹" + amount + " to " + beneficiary + " via " + channel;

        String transactionContext = String.format("""
                Analyze this transaction for fraud risk:
                Session: %s
                User: %s
                Amount: ₹%.2f
                Beneficiary: %s
                Channel: %s
                Intent: %s""",
                sessionId, userId, amount, beneficiary, channel, intent);

        try {
            log.info("🔍 Fraud Agent analyzing: session={} user={} amount=₹{} beneficiary={} channel={}",
                    sessionId, userId, amount, beneficiary, channel);

            String agentResponse = fraudAgent.analyze(sessionId, transactionContext);
            log.debug("Fraud Agent response: {}", agentResponse);

            return parseAgentResponse(agentResponse, sessionId, userId, intent, amount, beneficiary, channel);

        } catch (Exception e) {
            log.warn("⚠️ Fraud Agent failed, falling back to deterministic analysis: {}", e.getMessage());
            return deterministicFallback(sessionId, userId, intent, amount, beneficiary, channel);
        }
    }

    private FraudAnalysisResult parseAgentResponse(String response, String sessionId, String userId,
                                                    String intent, double amount,
                                                    String beneficiary, String channel) {
        Matcher decisionMatcher = DECISION_PATTERN.matcher(response);
        Matcher riskMatcher = RISK_SCORE_PATTERN.matcher(response);
        Matcher signalsMatcher = SIGNALS_PATTERN.matcher(response);

        if (!decisionMatcher.find() || !riskMatcher.find()) {
            log.warn("⚠️ Could not parse Fraud Agent response, falling back to deterministic");
            return deterministicFallback(sessionId, userId, intent, amount, beneficiary, channel);
        }

        FraudAction llmDecision = FraudAction.valueOf(decisionMatcher.group(1).toUpperCase());
        double riskScore = Double.parseDouble(riskMatcher.group(1));
        riskScore = Math.max(0.0, Math.min(1.0, riskScore));

        List<String> signals = List.of();
        if (signalsMatcher.find()) {
            String signalsStr = signalsMatcher.group(1).trim();
            if (!"NONE".equalsIgnoreCase(signalsStr) && !signalsStr.isBlank()) {
                signals = Arrays.stream(signalsStr.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
            }
        }

        // Enforce threshold rules — never trust LLM decision blindly
        FraudAction action = fraudSignalAnalyzer.determineFraudAction(riskScore, signals);
        if (action != llmDecision) {
            log.warn("⚠️ LLM decision {} overridden to {} (risk={}, signals={})",
                    llmDecision, action, riskScore, signals);
        }

        double behavioralScore = fraudSignalAnalyzer.getBehavioralSimilarityScore(sessionId, userId);
        String ragContext = fraudSignalAnalyzer.retrieveFraudPatterns(intent);

        log.info("✅ Fraud Agent decision: {} (risk={}, signals={})", action, riskScore, signals);
        return new FraudAnalysisResult(riskScore, behavioralScore, signals, action, ragContext);
    }

    /**
     * Deterministic fallback when the LLM agent fails or returns unparseable output.
     * Uses FraudSignalAnalyzer directly — same logic, no LLM involved.
     */
    private FraudAnalysisResult deterministicFallback(String sessionId, String userId, String intent,
                                                       double amount, String beneficiary, String channel) {
        String ragContext = fraudSignalAnalyzer.retrieveFraudPatterns(intent);
        double behavioralScore = fraudSignalAnalyzer.getBehavioralSimilarityScore(sessionId, userId);
        List<String> signals = fraudSignalAnalyzer.analyzeFraudSignals(ragContext, intent, amount, beneficiary, channel);
        double riskScore = fraudSignalAnalyzer.computeRiskScore(behavioralScore, signals);
        FraudAction action = fraudSignalAnalyzer.determineFraudAction(riskScore, signals);

        log.info("✅ Fraud deterministic fallback: {} (risk={}, signals={})", action, riskScore, signals);
        return new FraudAnalysisResult(riskScore, behavioralScore, signals, action, ragContext);
    }
}

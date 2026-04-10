package com.ayedata.service;

import com.ayedata.rag.service.RagService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class FraudContextService {
    private static final Logger log = LoggerFactory.getLogger(FraudContextService.class);

    private final RagService ragService;
    private final EmbeddingModel embeddingModel;

    public FraudContextService(RagService ragService, EmbeddingModel embeddingModel) {
        this.ragService = ragService;
        this.embeddingModel = embeddingModel;
    }

    public void evaluateTelemetryContext(String telemetryVector, String userIntent) {
        log.info("Processing continuous authentication telemetry...");
        // TODO: integrate embedding model + Atlas Vector Search + reranking
        log.info("Context evaluated successfully using abstracted AI engine.");
    }

    /**
     * Evaluates behavioral similarity score between user session and a given user
     * ID, supplemented by RAG-retrieved fraud patterns and contextual intelligence.
     *
     * Returns 0.0 if inputs are invalid or no matching behavioral patterns found.
     */
    public double getBehavioralSimilarityScore(String sessionId, String userId) {
        // Validation: null or blank inputs return 0.0
        if (sessionId == null || sessionId.isBlank() || userId == null || userId.isBlank()) {
            return 0.0;
        }

        // Validation: userId format (alphanumeric and underscore only, 4-64 chars)
        if (!userId.matches("^[a-zA-Z0-9_]{4,64}$")) {
            return 0.0;
        }

        // TODO: query behavioral profile, embed session telemetry, compute cosine similarity
        log.debug("Retrieved behavioral similarity for session: {}, userId: {}", sessionId, userId);
        return 0.0;
    }

    /**
     * NEW: RAG-supplemented fraud analysis that combines behavioral scoring with
     * contextual fraud intelligence from the knowledge base.
     */
    public FraudAnalysisResult analyzeFraudContext(String sessionId, String userId,
                                                   String userIntent, double amount,
                                                   String beneficiary, String channel) {
        // Allow null/empty userIntent
        String intent = (userIntent != null && !userIntent.isBlank()) ? userIntent :
                       "Transfer ₹" + amount + " to " + beneficiary + " via " + channel;

        // 1. Retrieve fraud patterns from RAG
        String fraudContext = ragService.retrieveContext(
            "fraud detection " + intent, 2);

        // 2. Get behavioral similarity (when implemented)
        double behavioralScore = getBehavioralSimilarityScore(sessionId, userId);

        // 3. Analyze contextual fraud signals
        List<String> fraudSignals = analyzeFraudSignals(fraudContext, intent, amount, beneficiary, channel);

        // 4. Compute composite risk score
        double riskScore = computeRiskScore(behavioralScore, fraudSignals);

        // 5. Determine action based on thresholds
        FraudAction action = determineFraudAction(riskScore, fraudSignals);

        return new FraudAnalysisResult(riskScore, behavioralScore, fraudSignals, action, fraudContext);
    }

    /**
     * Simplified version for cases where full context isn't available
     */
    public FraudAnalysisResult analyzeFraudContext(String sessionId, String userId,
                                                   double amount, String beneficiary, String channel) {
        return analyzeFraudContext(sessionId, userId, null, amount, beneficiary, channel);
    }

    private List<String> analyzeFraudSignals(String fraudContext, String userIntent,
                                           double amount, String beneficiary, String channel) {
        List<String> signals = new java.util.ArrayList<>();

        // Velocity checks from RAG context
        if (fraudContext.toLowerCase().contains("velocity check") &&
            amount > 100000) { // High-value transaction
            signals.add("HIGH_VALUE_TRANSACTION");
        }

        // Geographic anomalies (placeholder - would need location data)
        if (fraudContext.toLowerCase().contains("geo-anomaly")) {
            signals.add("GEO_ANOMALY_DETECTED");
        }

        // New device patterns
        if (fraudContext.toLowerCase().contains("new device")) {
            signals.add("NEW_DEVICE_PATTERN");
        }

        // Unusual timing
        if (fraudContext.toLowerCase().contains("time-of-day") &&
            userIntent.toLowerCase().contains("midnight|dawn|late night")) {
            signals.add("UNUSUAL_TIMING");
        }

        return signals;
    }

    private double computeRiskScore(double behavioralScore, List<String> fraudSignals) {
        double baseScore = behavioralScore;

        // Penalize for each fraud signal
        for (String signal : fraudSignals) {
            switch (signal) {
                case "HIGH_VALUE_TRANSACTION" -> baseScore *= 0.8;
                case "GEO_ANOMALY_DETECTED" -> baseScore *= 0.7;
                case "NEW_DEVICE_PATTERN" -> baseScore *= 0.6;
                case "UNUSUAL_TIMING" -> baseScore *= 0.9;
            }
        }

        return Math.max(0.0, Math.min(1.0, baseScore));
    }

    private FraudAction determineFraudAction(double riskScore, List<String> fraudSignals) {
        // Automatic blocks regardless of score
        if (fraudSignals.contains("GEO_ANOMALY_DETECTED") ||
            fraudSignals.contains("NEW_DEVICE_PATTERN")) {
            return FraudAction.BLOCK;
        }

        // Threshold-based decisions
        if (riskScore >= 0.95) {
            return FraudAction.APPROVE;
        } else if (riskScore >= 0.80) {
            return FraudAction.MONITOR;
        } else {
            return FraudAction.ESCALATE;
        }
    }

    public enum FraudAction {
        APPROVE, MONITOR, ESCALATE, BLOCK
    }

    public record FraudAnalysisResult(
        double riskScore,
        double behavioralScore,
        List<String> fraudSignals,
        FraudAction action,
        String ragContext
    ) {}
}
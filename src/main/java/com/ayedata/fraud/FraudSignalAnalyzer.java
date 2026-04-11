package com.ayedata.fraud;

import com.ayedata.domain.FinancialData;
import com.ayedata.domain.TransactionRecord;
import com.ayedata.rag.service.RagService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Core fraud signal analysis engine. Provides deterministic methods for
 * RAG retrieval, behavioral scoring, signal detection, and risk computation.
 * These methods are exposed as LangChain4j @Tool methods via {@link FraudTools}.
 */
@Service
public class FraudSignalAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(FraudSignalAnalyzer.class);

    private final RagService ragService;
    private final EmbeddingModel embeddingModel;
    private final FraudConfig fraudConfig;
    private final MongoTemplate mongoTemplate;

    public FraudSignalAnalyzer(RagService ragService, EmbeddingModel embeddingModel,
                               FraudConfig fraudConfig, MongoTemplate mongoTemplate) {
        this.ragService = ragService;
        this.embeddingModel = embeddingModel;
        this.fraudConfig = fraudConfig;
        this.mongoTemplate = mongoTemplate;
    }

    public String retrieveFraudPatterns(String intent) {
        return ragService.retrieveContext("fraud detection " + intent, 2);
    }

    public double getBehavioralSimilarityScore(String sessionId, String userId) {
        if (sessionId == null || sessionId.isBlank() || userId == null || userId.isBlank()) {
            return 0.0;
        }
        if (!userId.matches("^[a-zA-Z0-9_]{4,64}$")) {
            return 0.0;
        }

        try {
            Instant lookbackStart = Instant.now().minus(fraudConfig.getBehavioralLookbackDays(), ChronoUnit.DAYS);
            Query query = new Query(Criteria.where("userId").is(userId)
                    .and("createdAt").gte(lookbackStart)
                    .and("status").in("COMPLETED", "SUCCESS"))
                    .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                    .limit(50);

            List<TransactionRecord> history = mongoTemplate.find(query, TransactionRecord.class);

            if (history.size() < fraudConfig.getBehavioralMinTransactions()) {
                log.debug("Behavioral score: insufficient history for userId={} (found={}, min={})",
                        userId, history.size(), fraudConfig.getBehavioralMinTransactions());
                return 0.0; // Not enough history to score — triggers baseline
            }

            // Compute behavioral profile from historical transactions
            double avgAmount = history.stream()
                    .map(TransactionRecord::getFinancialData)
                    .filter(fd -> fd != null)
                    .mapToDouble(FinancialData::getAmount)
                    .average()
                    .orElse(0.0);

            double stdDevAmount = 0.0;
            if (avgAmount > 0) {
                double variance = history.stream()
                        .map(TransactionRecord::getFinancialData)
                        .filter(fd -> fd != null)
                        .mapToDouble(fd -> Math.pow(fd.getAmount() - avgAmount, 2))
                        .average()
                        .orElse(0.0);
                stdDevAmount = Math.sqrt(variance);
            }

            // Frequency: average transactions per week in the lookback window
            long daySpan = Math.max(1, ChronoUnit.DAYS.between(lookbackStart, Instant.now()));
            double txnsPerWeek = (history.size() * 7.0) / daySpan;

            // Most common channel
            String dominantChannel = history.stream()
                    .map(TransactionRecord::getPaymentMethod)
                    .filter(pm -> pm != null && !pm.isBlank())
                    .collect(java.util.stream.Collectors.groupingBy(
                            java.util.function.Function.identity(),
                            java.util.stream.Collectors.counting()))
                    .entrySet().stream()
                    .max(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey)
                    .orElse("UNKNOWN");

            // Score = weighted similarity across dimensions (0.0 = anomalous, 1.0 = normal)
            // For now, compute a profile consistency score based on amount regularity
            double amountConsistency;
            if (stdDevAmount == 0 || avgAmount == 0) {
                amountConsistency = 1.0; // All same amounts = perfectly consistent
            } else {
                // Coefficient of variation: lower = more consistent
                double cv = stdDevAmount / avgAmount;
                amountConsistency = Math.max(0.0, 1.0 - (cv / 2.0)); // cv=2 → score=0
            }

            // Frequency consistency: penalize if very infrequent (< 0.5/week)
            double frequencyScore = Math.min(1.0, txnsPerWeek / 2.0);

            // Weighted composite
            double behavioralScore = (amountConsistency * 0.6) + (frequencyScore * 0.4);
            behavioralScore = Math.max(0.0, Math.min(1.0, behavioralScore));

            log.debug("Behavioral score for userId={}: {} (avgAmt={}, stdDev={}, txns/wk={}, channel={})",
                    userId, behavioralScore, avgAmount, stdDevAmount, txnsPerWeek, dominantChannel);

            return behavioralScore;

        } catch (Exception e) {
            log.warn("Behavioral scoring failed for userId={}: {}", userId, e.getMessage());
            return 0.0; // Safe fallback — triggers baseline score
        }
    }

    public List<String> analyzeFraudSignals(String fraudContext, String userIntent,
                                            double amount, String beneficiary, String channel) {
        List<String> signals = new ArrayList<>();

        if (fraudContext.toLowerCase().contains("velocity check") && amount > fraudConfig.getHighValueThreshold()) {
            signals.add("HIGH_VALUE_TRANSACTION");
        }
        if (fraudContext.toLowerCase().contains("geo-anomaly")) {
            signals.add("GEO_ANOMALY_DETECTED");
        }
        if (fraudContext.toLowerCase().contains("new device")) {
            signals.add("NEW_DEVICE_PATTERN");
        }
        if (fraudContext.toLowerCase().contains("time-of-day") &&
            userIntent.toLowerCase().matches(".*(?:midnight|dawn|late night).*")) {
            signals.add("UNUSUAL_TIMING");
        }

        return signals;
    }

    public double computeRiskScore(double behavioralScore, List<String> fraudSignals) {
        double baseScore = (behavioralScore > 0.0) ? behavioralScore : fraudConfig.getBaselineScore();

        for (String signal : fraudSignals) {
            baseScore *= fraudConfig.getMultiplierForSignal(signal);
        }

        return Math.max(0.0, Math.min(1.0, baseScore));
    }

    public FraudAction determineFraudAction(double riskScore, List<String> fraudSignals) {
        // Any hardblock signal → automatic rejection regardless of score
        for (String signal : fraudSignals) {
            if (fraudConfig.getHardblockSignals().contains(signal)) {
                return FraudAction.BLOCK;
            }
        }

        // Threshold-based decisions
        if (riskScore >= fraudConfig.getThresholdApprove()) {
            return FraudAction.APPROVE;
        } else if (riskScore >= fraudConfig.getThresholdMonitor()) {
            return FraudAction.MONITOR;
        } else {
            return FraudAction.ESCALATE;
        }
    }
}

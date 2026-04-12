package com.ayedata.fraud;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * LangChain4j @Tool methods for the Fraud Agent.
 * Each method wraps a deterministic operation from {@link FraudSignalAnalyzer},
 * returning human-readable strings that the LLM can reason about.
 */
@Component
public class FraudTools {

    private final FraudSignalAnalyzer analyzer;

    public FraudTools(FraudSignalAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Tool("Retrieve fraud intelligence patterns from the payments knowledge base for a transaction context. " +
          "Call this FIRST to get RAG context before analyzing signals.")
    public String retrieveFraudPatterns(
            @P("Transaction description, e.g. 'Transfer ₹5000 to Ramesh via UPI'") String intent) {
        String context = analyzer.retrieveFraudPatterns(intent);
        return context.isBlank() ? "No fraud patterns found in knowledge base." : context;
    }

    @Tool("Get behavioral similarity score for a user session. " +
          "Returns a score between 0.0 and 1.0 indicating how closely the current session matches known user behavior.")
    public String checkBehavioralScore(
            @P("Session ID") String sessionId,
            @P("User ID") String userId) {
        double score = analyzer.getBehavioralSimilarityScore(sessionId, userId);
        return String.format("Behavioral similarity score: %.2f (%.0f%% match)", score, score * 100);
    }

    @Tool("Analyze a transaction for fraud signals based on RAG context and transaction details. " +
          "Returns detected signals such as HIGH_VALUE_TRANSACTION, GEO_ANOMALY_DETECTED, NEW_DEVICE_PATTERN, UNUSUAL_TIMING.")
    public String analyzeFraudSignals(
            @P("Fraud patterns retrieved from knowledge base") String ragContext,
            @P("Transaction amount in INR") double amount,
            @P("Beneficiary name or identifier") String beneficiary,
            @P("Payment channel (UPI, NEFT, RTGS, etc.)") String channel,
            @P("Original user intent or transaction description") String userIntent) {
        List<String> signals = analyzer.analyzeFraudSignals(ragContext, userIntent, amount, beneficiary, channel);
        if (signals.isEmpty()) {
            return "SIGNALS: NONE";
        }
        return "SIGNALS: " + String.join(", ", signals);
    }

    @Tool("Compute composite risk score from behavioral score and detected fraud signals. " +
          "Also determines the action: APPROVE (<0.30), MONITOR (0.30-0.49), ESCALATE (0.50-0.69), BLOCK (≥0.70 or hardblock signals).")
    public String computeRiskAndAction(
            @P("Behavioral similarity score (0.0-1.0), use 0.0 if unknown") double behavioralScore,
            @P("Comma-separated fraud signal names, or NONE") String signalsCsv) {
        List<String> signals = "NONE".equalsIgnoreCase(signalsCsv.trim()) || signalsCsv.isBlank()
                ? List.of()
                : Arrays.stream(signalsCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();

        double riskScore = analyzer.computeRiskScore(behavioralScore, signals);
        FraudAction action = analyzer.determineFraudAction(riskScore, signals);

        return String.format("RISK_SCORE: %.2f | ACTION: %s | SIGNALS: %s",
                riskScore, action.name(),
                signals.isEmpty() ? "NONE" : String.join(", ", signals));
    }
}

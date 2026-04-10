package com.ayedata.domain;

/**
 * Snapshot of the AI agent's reasoning at the time of transaction execution.
 */
public class AgentReasoning {
    private String supervisorDecision;
    private double contextSimilarityScore;
    private java.util.List<String> fraudSignals;
    private String ragFraudContext;

    public AgentReasoning() {}

    private AgentReasoning(Builder builder) {
        this.supervisorDecision = builder.supervisorDecision;
        this.contextSimilarityScore = builder.contextSimilarityScore;
        this.fraudSignals = builder.fraudSignals;
        this.ragFraudContext = builder.ragFraudContext;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String supervisorDecision;
        private double contextSimilarityScore;
        private java.util.List<String> fraudSignals;
        private String ragFraudContext;

        public Builder supervisorDecision(String supervisorDecision) {
            this.supervisorDecision = supervisorDecision;
            return this;
        }

        public Builder contextSimilarityScore(double contextSimilarityScore) {
            this.contextSimilarityScore = contextSimilarityScore;
            return this;
        }

        public Builder fraudSignals(java.util.List<String> fraudSignals) {
            this.fraudSignals = fraudSignals;
            return this;
        }

        public Builder ragFraudContext(String ragFraudContext) {
            this.ragFraudContext = ragFraudContext;
            return this;
        }

        public AgentReasoning build() {
            return new AgentReasoning(this);
        }
    }

    public String getSupervisorDecision() { return supervisorDecision; }
    public double getContextSimilarityScore() { return contextSimilarityScore; }
    public java.util.List<String> getFraudSignals() { return fraudSignals; }
    public String getRagFraudContext() { return ragFraudContext; }

    public void setSupervisorDecision(String supervisorDecision) { this.supervisorDecision = supervisorDecision; }
    public void setContextSimilarityScore(double contextSimilarityScore) { this.contextSimilarityScore = contextSimilarityScore; }
    public void setFraudSignals(java.util.List<String> fraudSignals) { this.fraudSignals = fraudSignals; }
    public void setRagFraudContext(String ragFraudContext) { this.ragFraudContext = ragFraudContext; }
}

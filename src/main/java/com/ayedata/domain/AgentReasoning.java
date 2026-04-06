package com.ayedata.domain;

/**
 * Snapshot of the AI agent's reasoning at the time of transaction execution.
 */
public class AgentReasoning {
    private String supervisorDecision;
    private double contextSimilarityScore;

    public AgentReasoning() {}

    private AgentReasoning(Builder builder) {
        this.supervisorDecision = builder.supervisorDecision;
        this.contextSimilarityScore = builder.contextSimilarityScore;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String supervisorDecision;
        private double contextSimilarityScore;

        public Builder supervisorDecision(String supervisorDecision) {
            this.supervisorDecision = supervisorDecision;
            return this;
        }

        public Builder contextSimilarityScore(double contextSimilarityScore) {
            this.contextSimilarityScore = contextSimilarityScore;
            return this;
        }

        public AgentReasoning build() {
            return new AgentReasoning(this);
        }
    }

    public String getSupervisorDecision() { return supervisorDecision; }
    public double getContextSimilarityScore() { return contextSimilarityScore; }

    public void setSupervisorDecision(String supervisorDecision) { this.supervisorDecision = supervisorDecision; }
    public void setContextSimilarityScore(double contextSimilarityScore) { this.contextSimilarityScore = contextSimilarityScore; }
}

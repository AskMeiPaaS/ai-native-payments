package com.ayedata.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Objects;

/**
 * Transaction Record with Queryable Encryption for PII fields.
 *
 * @see FinancialData
 * @see AgentReasoning
 * @see EncryptionMetadata
 */
@Document(collection = "transactions")
public class TransactionRecord {
    @Id
    private String id;
    private String sessionId;
    private String userId;
    private String instructionType;
    private String status;
    private FinancialData financialData;
    private AgentReasoning agentReasoningSnapshot;
    private Instant createdAt;
    private EncryptionMetadata encryptionMetadata;
    private Double resultingBalance;
    private String paymentMethod;  // e.g., "UPI", "NEFT", "RTGS"
    private Boolean requiresHitlReview;  // Indicates if HITL escalation is needed

    // Constructors
    public TransactionRecord() {}

    private TransactionRecord(Builder builder) {
        this.id = builder.id;
        this.sessionId = builder.sessionId;
        this.userId = builder.userId;
        this.instructionType = builder.instructionType;
        this.status = builder.status;
        this.financialData = builder.financialData;
        this.agentReasoningSnapshot = builder.agentReasoningSnapshot;
        this.createdAt = builder.createdAt;
        this.encryptionMetadata = builder.encryptionMetadata;
        this.resultingBalance = builder.resultingBalance;
        this.paymentMethod = builder.paymentMethod;
        this.requiresHitlReview = builder.requiresHitlReview;
    }

    // Builder
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String sessionId;
        private String userId;
        private String instructionType;
        private String status;
        private FinancialData financialData;
        private AgentReasoning agentReasoningSnapshot;
        private Instant createdAt;
        private EncryptionMetadata encryptionMetadata;
        private Double resultingBalance;
        private String paymentMethod;
        private Boolean requiresHitlReview;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder instructionType(String instructionType) {
            this.instructionType = instructionType;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder financialData(FinancialData financialData) {
            this.financialData = financialData;
            return this;
        }

        public Builder agentReasoningSnapshot(AgentReasoning agentReasoningSnapshot) {
            this.agentReasoningSnapshot = agentReasoningSnapshot;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder encryptionMetadata(EncryptionMetadata encryptionMetadata) {
            this.encryptionMetadata = encryptionMetadata;
            return this;
        }

        public Builder resultingBalance(Double resultingBalance) {
            this.resultingBalance = resultingBalance;
            return this;
        }

        public Builder paymentMethod(String paymentMethod) {
            this.paymentMethod = paymentMethod;
            return this;
        }

        public Builder requiresHitlReview(Boolean requiresHitlReview) {
            this.requiresHitlReview = requiresHitlReview;
            return this;
        }

        public TransactionRecord build() {
            return new TransactionRecord(this);
        }
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public String getInstructionType() {
        return instructionType;
    }

    public String getStatus() {
        return status;
    }

    public FinancialData getFinancialData() {
        return financialData;
    }

    public AgentReasoning getAgentReasoningSnapshot() {
        return agentReasoningSnapshot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public EncryptionMetadata getEncryptionMetadata() {
        return encryptionMetadata;
    }

    public Double getResultingBalance() {
        return resultingBalance;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public Boolean getRequiresHitlReview() {
        return requiresHitlReview;
    }

    // Setters
    public void setId(String id) {
        this.id = id;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setInstructionType(String instructionType) {
        this.instructionType = instructionType;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setFinancialData(FinancialData financialData) {
        this.financialData = financialData;
    }

    public void setAgentReasoningSnapshot(AgentReasoning agentReasoningSnapshot) {
        this.agentReasoningSnapshot = agentReasoningSnapshot;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setEncryptionMetadata(EncryptionMetadata encryptionMetadata) {
        this.encryptionMetadata = encryptionMetadata;
    }

    public void setResultingBalance(Double resultingBalance) {
        this.resultingBalance = resultingBalance;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public void setRequiresHitlReview(Boolean requiresHitlReview) {
        this.requiresHitlReview = requiresHitlReview;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransactionRecord that = (TransactionRecord) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "TransactionRecord{" +
                "id='" + id + '\'' +
                ", userId='" + userId + '\'' +
                ", instructionType='" + instructionType + '\'' +
                ", status='" + status + '\'' +
                ", resultingBalance=" + resultingBalance +
                ", createdAt=" + createdAt +
                '}';
    }
}
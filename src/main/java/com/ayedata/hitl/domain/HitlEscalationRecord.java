package com.ayedata.hitl.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "hitl_escalations")
public class HitlEscalationRecord {
    
    @Id
    private String id;
    private String escalationId;       // User-facing reference (e.g., "ESC_2026040412345")
    private String sessionId;
    private String transactionId;       // Generated after operator approves
    private String reasoning;
    private String status;
    private Instant createdAt;
    private String operatorId;          // WHO resolved it
    private String operatorNotes;       // Resolution notes from operator
    private Instant resolvedAt;         // WHEN it was resolved
    private String appealSource;        // USER_INITIATED or SYSTEM_INITIATED
    
    public HitlEscalationRecord() {}

    public HitlEscalationRecord(String sessionId, String reasoning, String status, Instant createdAt) {
        this.sessionId = sessionId;
        this.reasoning = reasoning;
        this.status = status;
        this.createdAt = createdAt;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEscalationId() { return escalationId; }
    public void setEscalationId(String escalationId) { this.escalationId = escalationId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }

    public String getOperatorNotes() { return operatorNotes; }
    public void setOperatorNotes(String operatorNotes) { this.operatorNotes = operatorNotes; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

    public String getAppealSource() { return appealSource; }
    public void setAppealSource(String appealSource) { this.appealSource = appealSource; }
}

package com.ayedata.hitl.dto;

import java.time.Instant;

/**
 * Response DTO for appeal status queries.
 */
public class AppealStatusResponse {
    private String escalationId;
    private String sessionId;
    private String status;
    private String transactionId;
    private String operatorMessage;
    private Instant createdAt;
    private Instant resolvedAt;

    public AppealStatusResponse(String escalationId, String sessionId, String status,
                               String transactionId, String operatorMessage,
                               Instant createdAt, Instant resolvedAt) {
        this.escalationId = escalationId;
        this.sessionId = sessionId;
        this.status = status;
        this.transactionId = transactionId;
        this.operatorMessage = operatorMessage;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
    }

    public String getEscalationId() { return escalationId; }
    public String getSessionId() { return sessionId; }
    public String getStatus() { return status; }
    public String getTransactionId() { return transactionId; }
    public String getOperatorMessage() { return operatorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }

    public String getMessage() {
        if ("PENDING_HUMAN_REVIEW".equals(status)) {
            return "Your appeal is being reviewed. Please wait...";
        } else if ("RESOLVED_APPROVED".equals(status) || "RESOLVED_MANUAL_OVERRIDE".equals(status)) {
            return "Transaction approved! Your confirmation ID is " + transactionId;
        } else if ("RESOLVED_DENIED".equals(status)) {
            return "Your appeal was not approved. " + (operatorMessage != null ? operatorMessage : "");
        } else if ("RESOLVED_CANCELLED".equals(status)) {
            return "Transaction cancelled. " + (operatorMessage != null ? operatorMessage : "");
        }
        return "Appeal status: " + status;
    }
}

package com.ayedata.hitl.dto;

public class AppealRequest {
    private String sessionId;
    private String appealReason;

    public String getSessionId() { return sessionId; }
    public String getAppealReason() { return appealReason; }

    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public void setAppealReason(String appealReason) { this.appealReason = appealReason; }
}

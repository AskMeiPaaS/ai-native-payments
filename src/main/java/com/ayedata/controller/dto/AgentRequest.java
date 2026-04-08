package com.ayedata.controller.dto;

public class AgentRequest {
    private String sessionId;
    private String userId;
    private String userIntent;
    private String telemetryVector;

    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
    public String getUserIntent() { return userIntent; }
    public String getTelemetryVector() { return telemetryVector; }

    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public void setUserId(String userId) { this.userId = userId; }
    public void setUserIntent(String userIntent) { this.userIntent = userIntent; }
    public void setTelemetryVector(String telemetryVector) { this.telemetryVector = telemetryVector; }
}

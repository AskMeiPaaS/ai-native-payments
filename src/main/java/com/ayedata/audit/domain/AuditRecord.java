package com.ayedata.audit.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.Objects;

@Document(collection = "system_audit_logs")
public class AuditRecord {
    @Id
    private String id;
    private Instant timestamp;
    private String eventType; // e.g., "AI_EXECUTION_ERROR", "HITL_ESCALATION"
    private String traceId;
    private String sessionId;
    private String endpoint;
    private String httpMethod;
    private Integer statusCode;
    private Long latencyMs;
    private String actorType;
    private String message;
    private String requestPayload;
    private String responsePayload;
    private String stackTrace;
    private String resolution; // Required for Explainable AI (XAI)

    // Constructor
    private AuditRecord(Builder builder) {
        this.id = builder.id;
        this.timestamp = builder.timestamp;
        this.eventType = builder.eventType;
        this.traceId = builder.traceId;
        this.sessionId = builder.sessionId;
        this.endpoint = builder.endpoint;
        this.httpMethod = builder.httpMethod;
        this.statusCode = builder.statusCode;
        this.latencyMs = builder.latencyMs;
        this.actorType = builder.actorType;
        this.message = builder.message;
        this.requestPayload = builder.requestPayload;
        this.responsePayload = builder.responsePayload;
        this.stackTrace = builder.stackTrace;
        this.resolution = builder.resolution;
    }

    // Builder
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private Instant timestamp;
        private String eventType;
        private String traceId;
        private String sessionId;
        private String endpoint;
        private String httpMethod;
        private Integer statusCode;
        private Long latencyMs;
        private String actorType;
        private String message;
        private String requestPayload;
        private String responsePayload;
        private String stackTrace;
        private String resolution;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder httpMethod(String httpMethod) {
            this.httpMethod = httpMethod;
            return this;
        }

        public Builder statusCode(Integer statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        public Builder latencyMs(Long latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        public Builder actorType(String actorType) {
            this.actorType = actorType;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder requestPayload(String requestPayload) {
            this.requestPayload = requestPayload;
            return this;
        }

        public Builder responsePayload(String responsePayload) {
            this.responsePayload = responsePayload;
            return this;
        }

        public Builder stackTrace(String stackTrace) {
            this.stackTrace = stackTrace;
            return this;
        }

        public Builder resolution(String resolution) {
            this.resolution = resolution;
            return this;
        }

        public AuditRecord build() {
            return new AuditRecord(this);
        }
    }

    // Getters
    public String getId() {
        return id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public String getActorType() {
        return actorType;
    }

    public String getMessage() {
        return message;
    }

    public String getRequestPayload() {
        return requestPayload;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public String getResolution() {
        return resolution;
    }

    // Setters
    public void setId(String id) {
        this.id = id;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setRequestPayload(String requestPayload) {
        this.requestPayload = requestPayload;
    }

    public void setResponsePayload(String responsePayload) {
        this.responsePayload = responsePayload;
    }

    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AuditRecord that = (AuditRecord) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "AuditRecord{" +
                "id='" + id + '\'' +
                ", timestamp=" + timestamp +
                ", eventType='" + eventType + '\'' +
                ", traceId='" + traceId + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", endpoint='" + endpoint + '\'' +
                '}';
    }
}
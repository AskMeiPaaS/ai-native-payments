package com.ayedata.audit.service;

import com.ayedata.audit.domain.AuditRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Audit Logging Service: captures structured, session-aware audit trails for
 * chat turns, API exchanges, and system failures in the isolated audit store.
 */
@Service
public class AuditLoggingService {
    private static final Logger log = LoggerFactory.getLogger(AuditLoggingService.class);

    private static final int MAX_STACK_TRACE_ELEMENTS = 5;
    private static final int MAX_MESSAGE_CHARS = 2_000;
    private static final int MAX_PAYLOAD_CHARS = 8_000;
    private static final String NO_SESSION_ID = "NO_SESSION";

    private final MongoTemplate auditMongoTemplate;

    public AuditLoggingService(@Qualifier("auditMongoTemplate") MongoTemplate auditMongoTemplate) {
        this.auditMongoTemplate = auditMongoTemplate;
    }

    public void logErrorEvent(String eventType, String message, Throwable ex) {
        logErrorEvent(eventType, null, message, ex);
    }

    public void logErrorEvent(String eventType, String sessionId, String message, Throwable ex) {
        String traceId = UUID.randomUUID().toString();
        log.error("[TRACE: {}] Audit Event: {} - {}", traceId, eventType, message);

        AuditRecord auditRecord = AuditRecord.builder()
                .timestamp(Instant.now())
                .eventType(eventType)
                .traceId(traceId)
                .sessionId(normalizeSessionId(sessionId))
                .actorType("SYSTEM")
                .message(truncate(message, MAX_MESSAGE_CHARS))
                .stackTrace(ex != null ? getStackTraceAsString(ex) : null)
                .resolution("PENDING_HUMAN_REVIEW")
                .build();

        persistAsync(auditRecord);
    }

    public void logApiInteraction(String sessionId, String endpoint, String httpMethod, int statusCode,
                                  String requestPayload, String responsePayload, long latencyMs) {
        AuditRecord auditRecord = AuditRecord.builder()
                .timestamp(Instant.now())
                .eventType("API_EXCHANGE")
                .traceId(UUID.randomUUID().toString())
                .sessionId(normalizeSessionId(sessionId))
                .endpoint(endpoint)
                .httpMethod(httpMethod)
                .statusCode(statusCode)
                .latencyMs(latencyMs)
                .actorType("API")
                .message(String.format("%s %s -> %d (%d ms)", httpMethod, endpoint, statusCode, latencyMs))
                .requestPayload(truncate(requestPayload, MAX_PAYLOAD_CHARS))
                .responsePayload(truncate(responsePayload, MAX_PAYLOAD_CHARS))
                .resolution(statusCode >= 400 ? "API_FAILURE_REVIEW" : "CAPTURED")
                .build();

        persistAsync(auditRecord);
    }

    public void logChatTurn(String sessionId, String userMessage, String assistantMessage,
                            long latencyMs, String endpoint) {
        AuditRecord auditRecord = AuditRecord.builder()
                .timestamp(Instant.now())
                .eventType("CHAT_TURN")
                .traceId(UUID.randomUUID().toString())
                .sessionId(normalizeSessionId(sessionId))
                .endpoint(endpoint)
                .httpMethod("POST")
                .statusCode(200)
                .latencyMs(latencyMs)
                .actorType("USER_AGENT")
                .message("Captured full user/assistant exchange for long-term audit and model improvement")
                .requestPayload(truncate(userMessage, MAX_PAYLOAD_CHARS))
                .responsePayload(truncate(assistantMessage, MAX_PAYLOAD_CHARS))
                .resolution("CAPTURED_FOR_LONG_TERM_MEMORY")
                .build();

        persistAsync(auditRecord);
    }

    private void persistAsync(AuditRecord auditRecord) {
        Thread.ofVirtual().name("audit-" + auditRecord.getEventType()).start(() -> {
            try {
                auditMongoTemplate.save(auditRecord);
                log.debug("Audit event {} logged for session {} with traceId {}",
                        auditRecord.getEventType(), auditRecord.getSessionId(), auditRecord.getTraceId());
            } catch (Exception e) {
                log.error("Failed to write audit log to MongoDB", e);
            }
        });
    }

    private String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? NO_SESSION_ID : sessionId;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "... [truncated " + (trimmed.length() - maxLength) + " chars]";
    }

    private String getStackTraceAsString(Throwable ex) {
        List<StackTraceElement> elements = Arrays.asList(ex.getStackTrace());
        int limit = Math.min(MAX_STACK_TRACE_ELEMENTS, elements.size());
        StringBuilder sb = new StringBuilder(ex.toString()).append("\n");

        for (int i = 0; i < limit; i++) {
            sb.append("\tat ").append(elements.get(i)).append("\n");
        }

        if (elements.size() > limit) {
            sb.append("\t... ").append(elements.size() - limit).append(" more\n");
        }

        return sb.toString();
    }
}
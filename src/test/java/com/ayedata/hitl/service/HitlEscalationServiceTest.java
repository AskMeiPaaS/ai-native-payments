package com.ayedata.hitl.service;

import com.ayedata.audit.service.AuditLoggingService;
import com.ayedata.hitl.domain.HitlEscalationRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HitlEscalationServiceTest {

    @Mock
    private AuditLoggingService auditLoggingService;

    @Mock
    private MongoTemplate hitlTemplate;

    @InjectMocks
    private HitlEscalationService hitlEscalationService;

    @Test
    void freezeStateAndEscalate_withNullSessionId_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            hitlEscalationService.freezeStateAndEscalate(null, "reason"));
        assertEquals("sessionId is required for HITL escalation", exception.getMessage());
    }

    @Test
    void freezeStateAndEscalate_withBlankSessionId_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            hitlEscalationService.freezeStateAndEscalate("", "reason"));
        assertEquals("sessionId is required for HITL escalation", exception.getMessage());
    }

    @Test
    void freezeStateAndEscalate_withValidInputs_callsAuditLogging() {
        String sessionId = "test-session-123";
        String llmReasoning = "Low similarity score detected";

        hitlEscalationService.freezeStateAndEscalate(sessionId, llmReasoning);

        // Verify that audit logging was called with a message containing escalation ID, session, and reasoning
        verify(auditLoggingService).logErrorEvent(
            org.mockito.ArgumentMatchers.eq("HITL_ESCALATION"),
            org.mockito.ArgumentMatchers.eq(sessionId),
            org.mockito.ArgumentMatchers.argThat((String msg) ->
                msg.contains("Escalation requested by AI") &&
                msg.contains("(ID: ESC_") &&
                msg.contains("for session: " + sessionId) &&
                msg.contains("Reason: " + llmReasoning)
            ),
            org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    void freezeStateAndEscalate_withNullReasoning_worksCorrectly() {
        String sessionId = "test-session-123";

        assertDoesNotThrow(() ->
            hitlEscalationService.freezeStateAndEscalate(sessionId, null));
    }

    @Test
    void freezeStateAndEscalate_withEmptyReasoning_worksCorrectly() {
        String sessionId = "test-session-123";

        assertDoesNotThrow(() ->
            hitlEscalationService.freezeStateAndEscalate(sessionId, ""));
    }

    @Test
    void resolveEscalation_withMongoDocumentId_fallsBackAndSucceeds() {
        HitlEscalationRecord record = new HitlEscalationRecord("session-1", "reason", "PENDING_HUMAN_REVIEW", Instant.now());
        record.setId("mongo-id-123");
        record.setEscalationId("ESC_12345");

        when(hitlTemplate.findOne(any(Query.class), eq(HitlEscalationRecord.class))).thenReturn(null);
        when(hitlTemplate.findById("mongo-id-123", HitlEscalationRecord.class)).thenReturn(record);

        HitlEscalationRecord updated = hitlEscalationService.resolveEscalation("mongo-id-123", "APPROVED", "operator-1", "looks good");

        assertEquals("RESOLVED_APPROVED", updated.getStatus());
        assertEquals("operator-1", updated.getOperatorId());
        assertNotNull(updated.getTransactionId());
    }

    @Test
    void resolveEscalation_withEscalationReference_succeeds() {
        HitlEscalationRecord record = new HitlEscalationRecord("session-2", "reason", "PENDING_HUMAN_REVIEW", Instant.now());
        record.setId("mongo-id-456");
        record.setEscalationId("ESC_45678");

        when(hitlTemplate.findOne(any(Query.class), eq(HitlEscalationRecord.class))).thenReturn(record);

        HitlEscalationRecord updated = hitlEscalationService.resolveEscalation("ESC_45678", "DENIED", "operator-2", "not approved");

        assertEquals("RESOLVED_DENIED", updated.getStatus());
        assertEquals("operator-2", updated.getOperatorId());
        assertNull(updated.getTransactionId());
    }
}
package com.ayedata.hitl.service;

import com.ayedata.audit.service.AuditLoggingService;
import com.ayedata.hitl.domain.HitlEscalationRecord;
import com.ayedata.hitl.dto.AppealStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Random;

@Service
public class HitlEscalationService {
    private static final Logger log = LoggerFactory.getLogger(HitlEscalationService.class);
    private static final Random random = new Random();

    private final AuditLoggingService auditLoggingService;
    private final MongoTemplate hitlTemplate;

    // Manual constructor (Lombok @RequiredArgsConstructor not processed without
    // annotation processor)
    public HitlEscalationService(AuditLoggingService auditLoggingService,
                                 @Qualifier("hitlMongoTemplate") MongoTemplate hitlTemplate) {
        this.auditLoggingService = auditLoggingService;
        this.hitlTemplate = hitlTemplate;
    }

    /**
     * Generate a unique escalation ID (e.g., ESC_2026040412345)
     */
    private String generateEscalationId() {
        long timestamp = System.currentTimeMillis() / 1000; // seconds
        long randomPart = random.nextInt(100000);
        return String.format("ESC_%d%05d", timestamp, randomPart);
    }

    /**
     * Generate a unique transaction ID (e.g., TXN_PASS_55443322)
     */
    private String generateTransactionId() {
        long randomPart = random.nextInt(100000000);
        return String.format("TXN_PASS_%08d", randomPart);
    }

    public String freezeStateAndEscalate(String sessionId, String llmReasoning) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required for HITL escalation");
        }

        String escalationId = generateEscalationId();
        log.warn("HITL escalation triggered for session: {} | Escalation ID: {}", sessionId, escalationId);

        // ✅ Persist state freeze marker and allow human operator to resume or rollback.
        HitlEscalationRecord record = new HitlEscalationRecord(
                sessionId,
                llmReasoning,
                "PENDING_HUMAN_REVIEW",
                Instant.now()
        );
        record.setEscalationId(escalationId);
        record.setAppealSource("SYSTEM_INITIATED");
        hitlTemplate.save(record);
        log.info("✅ Persisted HITL state freeze marker for session: {} with escalation ID: {}", sessionId, escalationId);

        // Record an audit event in the isolated audit database.
        auditLoggingService.logErrorEvent(
                "HITL_ESCALATION",
                sessionId,
                "Escalation requested by AI (ID: " + escalationId + ") for session: " + sessionId + " | Reason: " + llmReasoning,
                null
        );

        return escalationId;
    }

    /**
     * Retrieve all pending HITL escalations waiting for human review
     */
    public List<HitlEscalationRecord> getPendingEscalations() {
        log.info("Fetching all pending HITL escalations...");
        Query query = new Query(Criteria.where("status").is("PENDING_HUMAN_REVIEW"));
        return hitlTemplate.find(query, HitlEscalationRecord.class);
    }

    /**
     * Retrieve a specific escalation by ID
     */
    public HitlEscalationRecord getEscalationById(String id) {
        log.info("Fetching HITL escalation: {}", id);
        return hitlTemplate.findById(id, HitlEscalationRecord.class);
    }

    /**
     * Retrieve escalations by escalation ID (user-facing reference).
     * Falls back to the Mongo document id so older UI callers still work.
     */
    public HitlEscalationRecord getEscalationByEscalationId(String escalationId) {
        log.info("Fetching HITL escalation by escalation ID: {}", escalationId);
        Query query = new Query(Criteria.where("escalationId").is(escalationId));
        HitlEscalationRecord record = hitlTemplate.findOne(query, HitlEscalationRecord.class);
        if (record == null) {
            record = hitlTemplate.findById(escalationId, HitlEscalationRecord.class);
        }
        return record;
    }

    /**
     * Retrieve escalations by session ID
     */
    public HitlEscalationRecord getEscalationBySessionId(String sessionId) {
        log.info("Fetching HITL escalation for session: {}", sessionId);
        Query query = new Query(Criteria.where("sessionId").is(sessionId)
                .and("status").is("PENDING_HUMAN_REVIEW"));
        return hitlTemplate.findOne(query, HitlEscalationRecord.class);
    }

    /**
     * Get appeal status for user (returns transactionId if approved)
     */
    public AppealStatusResponse getAppealStatus(String escalationId) {
        log.info("Fetching appeal status for escalation ID: {}", escalationId);
        HitlEscalationRecord record = getEscalationByEscalationId(escalationId);
        
        if (record == null) {
            throw new IllegalArgumentException("Escalation not found: " + escalationId);
        }

        return new AppealStatusResponse(
                record.getEscalationId(),
                record.getSessionId(),
                record.getStatus(),
                record.getTransactionId(),
                record.getOperatorNotes(),
                record.getCreatedAt(),
                record.getResolvedAt()
        );
    }

    /**
     * Resolve an escalation with operator decision and action
     * Generates transactionId if decision is APPROVED or MANUAL_OVERRIDE
     */
    public HitlEscalationRecord resolveEscalation(String escalationId, String decision, String operatorId, String operatorNotes) {
        log.info("Resolving HITL escalation {} with decision: {} by operator: {}", escalationId, decision, operatorId);
        
        HitlEscalationRecord record = getEscalationByEscalationId(escalationId);
        if (record == null) {
            throw new IllegalArgumentException("Escalation record not found: " + escalationId);
        }
        if (record.getStatus() != null && record.getStatus().startsWith("RESOLVED_")) {
            log.info("Escalation {} was already resolved as {}", escalationId, record.getStatus());
            return record;
        }

        // Update status based on decision: APPROVED, DENIED, MANUAL_OVERRIDE, CANCELLED
        record.setStatus("RESOLVED_" + decision);
        record.setOperatorId(operatorId);
        record.setOperatorNotes(operatorNotes);
        record.setResolvedAt(Instant.now());

        // Generate transaction ID if approved or manually overridden
        if ("APPROVED".equals(decision) || "MANUAL_OVERRIDE".equals(decision)) {
            String transactionId = generateTransactionId();
            record.setTransactionId(transactionId);
            log.info("✅ Generated transaction ID for escalation {}: {}", escalationId, transactionId);
        }

        hitlTemplate.save(record);
        log.info("✅ HITL escalation resolved: {} -> {} | TransactionID: {}", 
                escalationId, decision, record.getTransactionId());

        // Audit log the resolution
        auditLoggingService.logErrorEvent(
                "HITL_RESOLUTION",
                record.getSessionId(),
                "Escalation " + escalationId + " (Session " + record.getSessionId() + ") resolved with decision: " + decision +
                        " by operator: " + operatorId + " | TransactionID: " + record.getTransactionId(),
                null
        );

        return record;
    }

    /**
     * Appeal an AI decision by user request
     * Returns escalationId for user tracking
     */
    public String appealDecision(String sessionId, String appealReason) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required for appeal");
        }

        String escalationId = generateEscalationId();
        log.warn("User appeal initiated for session: {} | Escalation ID: {}", sessionId, escalationId);

        HitlEscalationRecord record = new HitlEscalationRecord(
                sessionId,
                "User Appeal: " + appealReason,
                "PENDING_HUMAN_REVIEW",
                Instant.now()
        );
        record.setEscalationId(escalationId);
        record.setAppealSource("USER_INITIATED");

        hitlTemplate.save(record);
        log.info("✅ User appeal recorded for session: {} with escalation ID: {}", sessionId, escalationId);

        auditLoggingService.logErrorEvent(
                "USER_APPEAL",
                sessionId,
                "User appealed with reference ID: " + escalationId + " for session " + sessionId + ": " + appealReason,
                null
        );

        return escalationId;
    }

}
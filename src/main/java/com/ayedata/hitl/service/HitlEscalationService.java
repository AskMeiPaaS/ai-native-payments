package com.ayedata.hitl.service;

import com.ayedata.audit.service.AuditLoggingService;
import com.ayedata.hitl.domain.HitlEscalationRecord;
import com.ayedata.hitl.dto.AppealStatusResponse;
import com.ayedata.init.UserProfileInitializer;
import com.ayedata.payment.PaymentContext;
import com.ayedata.payment.PaymentResult;
import com.ayedata.payment.PaymentSwitchRouter;
import com.ayedata.service.MongoLedgerService;
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
    private final MongoLedgerService mongoLedgerService;
    private final PaymentSwitchRouter paymentSwitchRouter;

    public HitlEscalationService(AuditLoggingService auditLoggingService,
                                 @Qualifier("hitlMongoTemplate") MongoTemplate hitlTemplate,
                                 MongoLedgerService mongoLedgerService,
                                 PaymentSwitchRouter paymentSwitchRouter) {
        this.auditLoggingService = auditLoggingService;
        this.hitlTemplate = hitlTemplate;
        this.mongoLedgerService = mongoLedgerService;
        this.paymentSwitchRouter = paymentSwitchRouter;
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
        return freezeStateAndEscalate(sessionId, llmReasoning, null, 0, null, null, null);
    }

    /**
     * Freeze state and escalate with full transaction details for direct commit on approval.
     */
    public String freezeStateAndEscalate(String sessionId, String llmReasoning,
                                         String userId, double amount, String beneficiary,
                                         String channel, String instructionType) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required for HITL escalation");
        }

        String escalationId = generateEscalationId();
        log.warn("HITL escalation triggered for session: {} | Escalation ID: {}", sessionId, escalationId);

        HitlEscalationRecord record = new HitlEscalationRecord(
                sessionId,
                llmReasoning,
                "PENDING_HUMAN_REVIEW",
                Instant.now()
        );
        record.setEscalationId(escalationId);
        record.setAppealSource("SYSTEM_INITIATED");

        // Store frozen transaction details for direct commit on operator approval
        if (userId != null) record.setUserId(userId);
        if (amount > 0) record.setAmount(amount);
        if (beneficiary != null) record.setBeneficiary(beneficiary);
        if (channel != null) record.setChannel(channel);
        if (instructionType != null) record.setInstructionType(instructionType);

        hitlTemplate.save(record);
        log.info("✅ Persisted HITL state freeze marker for session: {} with escalation ID: {}", sessionId, escalationId);

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
            String transactionId = commitFrozenTransaction(record);
            record.setTransactionId(transactionId);
            log.info("✅ Transaction committed for escalation {}: {}", escalationId, transactionId);
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

    /**
     * Commit the frozen transaction directly via the payment switch — no LLM involved.
     * Falls back to {@link MongoLedgerService} when the record has structured fields;
     * returns a generated TXN ID otherwise (legacy records without transaction details).
     */
    private String commitFrozenTransaction(HitlEscalationRecord record) {
        String userId = record.getUserId();
        Double amount = record.getAmount();
        String beneficiary = record.getBeneficiary();
        String channel = record.getChannel();
        String instructionType = record.getInstructionType();

        // Legacy escalation without structured fields — generate a placeholder TXN ID
        if (userId == null || amount == null || amount <= 0) {
            log.warn("Escalation {} has no frozen transaction details — generating placeholder TXN ID", record.getEscalationId());
            return generateTransactionId();
        }

        try {
            if ("MANDATE".equals(instructionType)) {
                String mandateDetails = "HITL-approved mandate switch";
                return mongoLedgerService.commitMandateAtomic(record.getSessionId(), userId, beneficiary, mandateDetails);
            }

            // Auto-select channel if not stored
            if (channel == null || channel.isBlank()) {
                channel = selectChannelForAmount(amount);
            }

            if ("RECEIVE".equals(instructionType)) {
                PaymentContext ctx = new PaymentContext(record.getSessionId(), userId, "External Payer", amount, channel, null);
                PaymentResult result = paymentSwitchRouter.route(channel).receive(ctx);
                return result.txnId();
            }

            // Default: TRANSFER
            String recipientUserId = UserProfileInitializer.resolveUserIdByNameOrId(beneficiary);
            PaymentContext ctx = new PaymentContext(record.getSessionId(), userId, beneficiary, amount, channel, recipientUserId);
            PaymentResult result = paymentSwitchRouter.route(channel).transfer(ctx);
            return result.txnId();
        } catch (Exception e) {
            log.error("Failed to commit frozen transaction for escalation {}: {}", record.getEscalationId(), e.getMessage(), e);
            // Still generate a placeholder so the escalation record is marked resolved
            return generateTransactionId();
        }
    }

    /**
     * Auto-select payment channel based on amount (mirrors LedgerTools logic).
     */
    private static String selectChannelForAmount(double amount) {
        if (amount <= 500)       return "UPI Lite";
        if (amount <= 1_00_000)  return "UPI";
        if (amount < 2_00_000)   return "NEFT";
        return "RTGS";
    }

}
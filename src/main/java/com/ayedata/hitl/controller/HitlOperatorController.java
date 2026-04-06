package com.ayedata.hitl.controller;

import com.ayedata.hitl.dto.OperatorDecision;
import com.ayedata.hitl.domain.HitlEscalationRecord;
import com.ayedata.hitl.service.HitlEscalationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * HitlOperatorController
 * 
 * REST endpoints for human operators to manage escalated transactions.
 * Allows operators to:
 * - View pending escalations
 * - Retrieve escalation details
 * - Approve or deny escalations
 * - Execute manual overrides
 */
@RestController
@RequestMapping("/api/v1/operator")
@CrossOrigin(origins = "${app.cors.allowed-origins:http://localhost:3000}", allowCredentials = "true")
public class HitlOperatorController {
    private static final Logger log = LoggerFactory.getLogger(HitlOperatorController.class);

    private final HitlEscalationService hitlEscalationService;

    public HitlOperatorController(HitlEscalationService hitlEscalationService) {
        this.hitlEscalationService = hitlEscalationService;
    }

    /**
     * GET /api/v1/operator/escalations/pending
     * 
     * Retrieve all pending HITL escalations waiting for human review.
     * 
     * @return List of pending escalation records
     */
    @GetMapping("/escalations/pending")
    public ResponseEntity<List<HitlEscalationRecord>> getPendingEscalations() {
        log.info("Operator request: Get pending escalations");
        List<HitlEscalationRecord> pending = hitlEscalationService.getPendingEscalations();
        log.info("Found {} pending escalations", pending.size());
        return ResponseEntity.ok(pending);
    }

    /**
     * GET /api/v1/operator/escalations/{escalationId}
     * 
     * Retrieve a specific escalation by escalation ID (user-facing reference).
     * 
     * @param escalationId User-facing escalation reference (e.g., ESC_2026040412345)
     * @return Escalation record details
     */
    @GetMapping("/escalations/{escalationId}")
    public ResponseEntity<HitlEscalationRecord> getEscalationById(@PathVariable String escalationId) {
        log.info("Operator request: Get escalation {}", escalationId);
        HitlEscalationRecord record = hitlEscalationService.getEscalationByEscalationId(escalationId);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

    /**
     * GET /api/v1/operator/escalations/session/{sessionId}
     * 
     * Retrieve escalation for a specific session ID.
     * 
     * @param sessionId User/transaction session ID
     * @return Escalation record for that session
     */
    @GetMapping("/escalations/session/{sessionId}")
    public ResponseEntity<HitlEscalationRecord> getEscalationBySessionId(@PathVariable String sessionId) {
        log.info("Operator request: Get escalation for session {}", sessionId);
        HitlEscalationRecord record = hitlEscalationService.getEscalationBySessionId(sessionId);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

    /**
     * POST /api/v1/operator/escalations/{escalationId}/approve
     * 
     * Operator approves the AI's proposed action and transaction proceeds.
     * Transaction ID is generated and returned.
     * 
     * @param escalationId User-facing escalation reference
     * @param request OperatorDecision with operatorId and notes
     * @return Updated escalation record with transactionId
     */
    @PostMapping("/escalations/{escalationId}/approve")
    public ResponseEntity<HitlEscalationRecord> approveEscalation(
            @PathVariable String escalationId,
            @RequestBody OperatorDecision request) {
        log.info("Operator {} approving escalation {}", request.getOperatorId(), escalationId);
        
        HitlEscalationRecord updated = hitlEscalationService.resolveEscalation(
                escalationId,
                "APPROVED",
                request.getOperatorId(),
                request.getOperatorNotes()
        );
        
        return ResponseEntity.ok(updated);
    }

    /**
     * POST /api/v1/operator/escalations/{escalationId}/deny
     * 
     * Operator denies the transaction and rolls back.
     * 
     * @param escalationId User-facing escalation reference
     * @param request OperatorDecision with operatorId and notes
     * @return Updated escalation record
     */
    @PostMapping("/escalations/{escalationId}/deny")
    public ResponseEntity<HitlEscalationRecord> denyEscalation(
            @PathVariable String escalationId,
            @RequestBody OperatorDecision request) {
        log.info("Operator {} denying escalation {}", request.getOperatorId(), escalationId);
        
        HitlEscalationRecord updated = hitlEscalationService.resolveEscalation(
                escalationId,
                "DENIED",
                request.getOperatorId(),
                request.getOperatorNotes()
        );
        
        return ResponseEntity.ok(updated);
    }

    /**
     * POST /api/v1/operator/escalations/{escalationId}/override
     * 
     * Operator manually overrides and executes a custom action.
     * Transaction ID is generated and returned.
     * 
     * @param escalationId User-facing escalation reference
     * @param request OperatorDecision with custom action intent
     * @return Updated escalation record with transactionId
     */
    @PostMapping("/escalations/{escalationId}/override")
    public ResponseEntity<HitlEscalationRecord> overrideEscalation(
            @PathVariable String escalationId,
            @RequestBody OperatorDecision request) {
        log.info("Operator {} executing manual override for escalation {}", request.getOperatorId(), escalationId);
        
        HitlEscalationRecord updated = hitlEscalationService.resolveEscalation(
                escalationId,
                "MANUAL_OVERRIDE",
                request.getOperatorId(),
                request.getOperatorNotes()
        );
        
        return ResponseEntity.ok(updated);
    }

    /**
     * POST /api/v1/operator/escalations/{escalationId}/cancel
     * 
     * Operator cancels the transaction entirely.
     * 
     * @param escalationId User-facing escalation reference
     * @param request OperatorDecision with cancellation reason
     * @return Updated escalation record
     */
    @PostMapping("/escalations/{escalationId}/cancel")
    public ResponseEntity<HitlEscalationRecord> cancelEscalation(
            @PathVariable String escalationId,
            @RequestBody OperatorDecision request) {
        log.info("Operator {} canceling escalation {}", request.getOperatorId(), escalationId);
        
        HitlEscalationRecord updated = hitlEscalationService.resolveEscalation(
                escalationId,
                "CANCELLED",
                request.getOperatorId(),
                request.getOperatorNotes()
        );
        
        return ResponseEntity.ok(updated);
    }

}

package com.ayedata.hitl.controller;

import com.ayedata.hitl.dto.AppealRequest;
import com.ayedata.hitl.dto.AppealStatusResponse;
import com.ayedata.hitl.service.HitlEscalationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Handles user appeal submission and status queries.
 */
@RestController
@RequestMapping("/api/v1/agent")
@CrossOrigin(origins = "${app.cors.allowed-origins:http://localhost:3000}", allowCredentials = "true")
public class AppealController {
    private static final Logger log = LoggerFactory.getLogger(AppealController.class);

    private final HitlEscalationService hitlEscalationService;

    public AppealController(HitlEscalationService hitlEscalationService) {
        this.hitlEscalationService = hitlEscalationService;
    }

    @PostMapping("/appeal")
    public ResponseEntity<Map<String, Object>> appealDecision(@RequestBody AppealRequest appealRequest) {
        log.info("User appeal received for session {}: {}", appealRequest.getSessionId(), appealRequest.getAppealReason());

        if (appealRequest.getSessionId() == null || appealRequest.getSessionId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sessionId is required"));
        }
        if (appealRequest.getAppealReason() == null || appealRequest.getAppealReason().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "appealReason is required"));
        }

        String escalationId = hitlEscalationService.appealDecision(
                appealRequest.getSessionId(), appealRequest.getAppealReason());

        return ResponseEntity.ok(Map.of(
                "message", "Your appeal has been recorded and will be reviewed by a human analyst",
                "escalationId", escalationId,
                "sessionId", appealRequest.getSessionId(),
                "status", "ESCALATED_TO_HUMAN",
                "estimatedResolutionTime", "5-10 minutes"
        ));
    }

    @GetMapping("/appeal-status/{escalationId}")
    public ResponseEntity<Map<String, Object>> getAppealStatus(@PathVariable String escalationId) {
        log.info("Appeal status query for escalation ID: {}", escalationId);

        try {
            AppealStatusResponse statusResponse =
                    hitlEscalationService.getAppealStatus(escalationId);

            Map<String, Object> response = Map.of(
                    "escalationId", statusResponse.getEscalationId(),
                    "sessionId", statusResponse.getSessionId(),
                    "status", statusResponse.getStatus(),
                    "transactionId", statusResponse.getTransactionId() != null ?
                            statusResponse.getTransactionId() : "",
                    "operatorMessage", statusResponse.getOperatorMessage() != null ?
                            statusResponse.getOperatorMessage() : "",
                    "createdAt", statusResponse.getCreatedAt(),
                    "resolvedAt", statusResponse.getResolvedAt(),
                    "message", statusResponse.getMessage()
            );

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("Escalation not found: {}", escalationId);
            return ResponseEntity.notFound().build();
        }
    }
}

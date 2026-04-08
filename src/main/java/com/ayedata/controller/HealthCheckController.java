package com.ayedata.controller;

import com.ayedata.ai.agent.PaSSOrchestratorAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health check endpoint for frontend to verify system connectivity.
 */
@RestController
@RequestMapping("/api/v1/agent")
@CrossOrigin(origins = "${app.cors.allowed-origins:http://localhost:3000}", allowCredentials = "true")
public class HealthCheckController {
    private static final Logger log = LoggerFactory.getLogger(HealthCheckController.class);

    private final PaSSOrchestratorAgent orchestratorAgent;

    public HealthCheckController(PaSSOrchestratorAgent orchestratorAgent) {
        this.orchestratorAgent = orchestratorAgent;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        log.debug("Health check request");

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());
        health.put("backend", "ONLINE");
        health.put("toolsAvailable", orchestratorAgent.areToolsAvailable());

        return ResponseEntity.ok(health);
    }
}

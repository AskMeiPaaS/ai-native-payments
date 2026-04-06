package com.ayedata.controller;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
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

    private final MongoTemplate primaryMongoTemplate;

    public HealthCheckController(@Qualifier("primaryMongoTemplate") MongoTemplate primaryMongoTemplate) {
        this.primaryMongoTemplate = primaryMongoTemplate;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        log.debug("Health check request");

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());
        health.put("backend", "ONLINE");

        try {
            Document pingCommand = new Document("hello", 1);
            var mongoDatabase = primaryMongoTemplate.getDb();

            if (mongoDatabase != null) {
                Document result = mongoDatabase.runCommand(pingCommand);

                if (result != null) {
                    Object okValue = result.get("ok");
                    boolean isConnected = false;

                    if (okValue instanceof Boolean) {
                        isConnected = (Boolean) okValue;
                    } else if (okValue instanceof Number) {
                        isConnected = ((Number) okValue).intValue() == 1;
                    }

                    if (isConnected) {
                        health.put("mongodb", "CONNECTED");
                        health.put("mongodbStatus", true);
                        health.put("mongoVersion", result.get("version", "unknown"));
                        log.info("✅ MongoDB health check passed - db.hello() successful");
                    } else {
                        health.put("mongodb", "DISCONNECTED");
                        health.put("mongodbStatus", false);
                        health.put("reason", "db.hello() returned ok=0 or false");
                        log.warn("⚠️ MongoDB health check failed");
                    }
                } else {
                    health.put("mongodb", "DISCONNECTED");
                    health.put("mongodbStatus", false);
                    health.put("reason", "db.hello() returned null result");
                }
            } else {
                health.put("mongodb", "DISCONNECTED");
                health.put("mongodbStatus", false);
                health.put("reason", "MongoDatabase instance is null");
            }
        } catch (Exception e) {
            health.put("mongodb", "DISCONNECTED");
            health.put("mongodbStatus", false);
            health.put("mongodbError", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("❌ MongoDB health check exception: ", e);
        }

        return ResponseEntity.ok(health);
    }
}

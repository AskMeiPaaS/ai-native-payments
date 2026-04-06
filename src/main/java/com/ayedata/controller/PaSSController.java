package com.ayedata.controller;

import com.ayedata.ai.agent.PaSSOrchestratorAgent;
import com.ayedata.controller.dto.AgentRequest;
import com.ayedata.audit.service.AuditLoggingService;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ayedata.controller.SseEmitterHelper.*;

/**
 * Core PaSS orchestration endpoints — synchronous and streaming (SSE).
 */
@RestController
@RequestMapping("/api/v1/agent")
@CrossOrigin(origins = "${app.cors.allowed-origins:http://localhost:3000}", allowCredentials = "true")
public class PaSSController {
    private static final Logger log = LoggerFactory.getLogger(PaSSController.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final PaSSOrchestratorAgent orchestratorAgent;
    private final AuditLoggingService auditLoggingService;

    public PaSSController(PaSSOrchestratorAgent orchestratorAgent,
                          AuditLoggingService auditLoggingService) {
        this.orchestratorAgent = orchestratorAgent;
        this.auditLoggingService = auditLoggingService;
    }

    @PostMapping("/orchestrate")
    public ResponseEntity<Map<String, String>> orchestrateRequest(@RequestBody AgentRequest request) {
        log.info("Received PaSS intent for session {}: {}", request.getSessionId(), request.getUserIntent());
        long startTime = System.currentTimeMillis();

        try {
            String agentReply = orchestratorAgent.orchestrateSwitch(
                    request.getSessionId(), request.getUserIntent());
            long elapsedMs = System.currentTimeMillis() - startTime;

            auditLoggingService.logChatTurn(
                    request.getSessionId(),
                    request.getUserIntent(),
                    agentReply,
                    elapsedMs,
                    "/api/v1/agent/orchestrate"
            );

            return ResponseEntity.ok(Map.of("reply", agentReply));
        } catch (RuntimeException ex) {
            auditLoggingService.logErrorEvent(
                    "SYNC_ORCHESTRATION_FAILED",
                    request.getSessionId(),
                    "Synchronous orchestration failed for user intent: " + request.getUserIntent(),
                    ex
            );
            throw ex;
        }
    }

    @PostMapping("/orchestrate-stream")
    public SseEmitter orchestrateStreamingRequest(@RequestBody AgentRequest request) throws IOException {
        log.info("Received streaming PaSS intent for session {}: {}", request.getSessionId(), request.getUserIntent());

        SseEmitter emitter = new SseEmitter(600000L);
        AtomicBoolean emitterCompleted = new AtomicBoolean(false);

        // Register lifecycle callbacks so we stop sending when client disconnects
        emitter.onTimeout(() -> {
            log.warn("Session {}: SSE emitter timed out", request.getSessionId());
            emitterCompleted.set(true);
        });
        emitter.onCompletion(() -> {
            log.debug("Session {}: SSE emitter completed", request.getSessionId());
            emitterCompleted.set(true);
        });
        emitter.onError(e -> {
            log.warn("Session {}: SSE emitter error: {}", request.getSessionId(),
                    e != null ? e.getMessage() : "unknown");
            emitterCompleted.set(true);
        });

        Thread.ofVirtual().name("stream-" + request.getSessionId()).start(() -> {
            try {
                long startTime = System.currentTimeMillis();

                // Start event
                Map<String, Object> startData = new LinkedHashMap<>();
                startData.put("timestamp", startTime);
                startData.put("message", "🔄 Processing your request...");
                SseEmitter.SseEventBuilder startEvent = SseEmitter.event()
                        .id(request.getSessionId())
                        .name("start")
                        .data(objectMapper.writeValueAsString(startData))
                        .reconnectTime(1000);
                if (!trySendEvent(emitter, startEvent, request.getSessionId(), emitterCompleted)) return;

                // Heartbeat thread — fires every 15 s to keep the SSE connection alive.
                // This prevents Node.js / browser idle-connection timeouts during the silent
                // gaps in Ollama inference (especially between a tool call and the second LLM
                // response, which can be several minutes on slow hardware).
                AtomicBoolean heartbeatDone = new AtomicBoolean(false);
                Thread.ofVirtual().name("heartbeat-" + request.getSessionId()).start(() -> {
                    try {
                        while (!emitterCompleted.get() && !heartbeatDone.get()) {
                            Thread.sleep(15_000);
                            if (emitterCompleted.get() || heartbeatDone.get()) break;
                            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                            Map<String, Object> hbData = new LinkedHashMap<>();
                            hbData.put("elapsed", elapsed);
                            SseEmitter.SseEventBuilder hbEvent = SseEmitter.event()
                                    .id(request.getSessionId())
                                    .name("heartbeat")
                                    .data(objectMapper.writeValueAsString(hbData))
                                    .reconnectTime(1000);
                            trySendEvent(emitter, hbEvent, request.getSessionId(), emitterCompleted);
                            log.debug("Session {}: heartbeat ({}s)", request.getSessionId(), elapsed);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        log.debug("Session {}: heartbeat error: {}", request.getSessionId(), e.getMessage());
                    }
                });

                // Get the token stream from the streaming supervisor
                TokenStream tokenStream = orchestratorAgent.orchestrateSwitchStreaming(
                        request.getSessionId(), request.getUserIntent());

                AtomicInteger charCount = new AtomicInteger(0);
                StringBuilder fullReply = new StringBuilder();

                tokenStream
                        .onPartialResponse(token -> {
                            if (token == null || token.isEmpty()) return;
                            // Always accumulate — LLM runs to completion in the background
                            // even after the client disconnects. This ensures audit and
                            // temporal archive always receive the full response.
                            fullReply.append(token);
                            if (emitterCompleted.get()) return; // client gone; skip SSE send
                            try {
                                Map<String, Object> chunkData = new LinkedHashMap<>();
                                chunkData.put("content", token);
                                chunkData.put("charCount", charCount.addAndGet(token.length()));
                                SseEmitter.SseEventBuilder chunkEvent = SseEmitter.event()
                                        .id(request.getSessionId())
                                        .name("chunk")
                                        .data(objectMapper.writeValueAsString(chunkData))
                                        .reconnectTime(1000);
                                trySendEvent(emitter, chunkEvent, request.getSessionId(), emitterCompleted);
                            } catch (Exception e) {
                                log.warn("Session {}: Error sending chunk", request.getSessionId(), e);
                            }
                        })
                        .onCompleteResponse(response -> {
                            try {
                                heartbeatDone.set(true); // stop heartbeat thread
                                long elapsedMs = System.currentTimeMillis() - startTime;
                                Map<String, Object> completeData = new LinkedHashMap<>();
                                completeData.put("elapsedMs", elapsedMs);
                                completeData.put("message", String.format("✅ Completed in %.2f seconds", elapsedMs / 1000.0));
                                SseEmitter.SseEventBuilder completeEvent = SseEmitter.event()
                                        .id(request.getSessionId())
                                        .name("complete")
                                        .data(objectMapper.writeValueAsString(completeData))
                                        .reconnectTime(1000);
                                trySendEvent(emitter, completeEvent, request.getSessionId(), emitterCompleted);
                                tryCompleteEmitter(emitter, request.getSessionId(), emitterCompleted);
                                log.info("✅ Session {}: Streaming response sent ({} ms, {} chars)",
                                        request.getSessionId(), elapsedMs, charCount.get());

                                // Archive and audit the completed turn asynchronously
                                String reply = fullReply.toString();
                                if (!reply.isBlank()) {
                                    auditLoggingService.logChatTurn(
                                            request.getSessionId(),
                                            request.getUserIntent(),
                                            reply,
                                            elapsedMs,
                                            "/api/v1/agent/orchestrate-stream"
                                    );
                                    Thread.ofVirtual().name("temporal-archive-" + request.getSessionId())
                                            .start(() -> orchestratorAgent.getTemporalMemoryService()
                                                    .archiveTurn(request.getSessionId(), request.getUserIntent(), reply));
                                }
                            } catch (Exception e) {
                                log.warn("Session {}: Error sending complete event", request.getSessionId(), e);
                            }
                        })
                        .onError(error -> {
                            heartbeatDone.set(true); // stop heartbeat thread
                            log.error("Session {}: Streaming error from LLM", request.getSessionId(), error);
                            auditLoggingService.logErrorEvent(
                                    "STREAMING_ORCHESTRATION_FAILED",
                                    request.getSessionId(),
                                    "Streaming orchestration failed for user intent: " + request.getUserIntent(),
                                    error
                            );
                            tryCompleteEmitterWithError(emitter, new RuntimeException(error),
                                    request.getSessionId(), emitterCompleted);
                        })
                        .start();

            } catch (Exception e) {
                log.error("Session {}: Streaming setup error", request.getSessionId(), e);
                auditLoggingService.logErrorEvent(
                        "STREAMING_SETUP_FAILED",
                        request.getSessionId(),
                        "Failed to initialize streaming for user intent: " + request.getUserIntent(),
                        e
                );
                tryCompleteEmitterWithError(emitter, e, request.getSessionId(), emitterCompleted);
            }
        });

        return emitter;
    }
}

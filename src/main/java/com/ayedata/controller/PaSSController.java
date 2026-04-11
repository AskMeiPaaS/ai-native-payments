package com.ayedata.controller;

import com.ayedata.ai.agent.PaSSOrchestratorAgent;
import com.ayedata.config.OllamaMetricsScheduler;
import com.ayedata.controller.dto.AgentRequest;
import com.ayedata.audit.service.AuditLoggingService;
import com.ayedata.config.MongoChatMemoryStore;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
    private final MongoChatMemoryStore chatMemoryStore;
    private final OllamaMetricsScheduler ollamaMetrics;

    public PaSSController(PaSSOrchestratorAgent orchestratorAgent,
                          AuditLoggingService auditLoggingService,
                          MongoChatMemoryStore chatMemoryStore,
                          OllamaMetricsScheduler ollamaMetrics) {
        this.orchestratorAgent = orchestratorAgent;
        this.auditLoggingService = auditLoggingService;
        this.chatMemoryStore = chatMemoryStore;
        this.ollamaMetrics = ollamaMetrics;
    }

    /**
     * Retrieve displayable chat history for a session.
     * Returns only USER and AI messages (no system/tool-execution internals).
     */
    @GetMapping("/chat-history")
    public ResponseEntity<Map<String, Object>> getChatHistory(@RequestParam String sessionId) {
        List<ChatMessage> allMessages = chatMemoryStore.getMessages(sessionId);

        List<Map<String, String>> history = new ArrayList<>();
        for (ChatMessage msg : allMessages) {
            if (msg instanceof UserMessage um) {
                String text = um.contents().stream()
                        .filter(c -> c instanceof TextContent)
                        .map(c -> ((TextContent) c).text())
                        .collect(Collectors.joining("\n"));
                // Strip the enriched context — only keep text after [CURRENT REQUEST]
                int markerIdx = text.indexOf("[CURRENT REQUEST]");
                String displayText = (markerIdx >= 0)
                        ? text.substring(markerIdx + "[CURRENT REQUEST]".length()).trim()
                        : text;
                if (!displayText.isBlank()) {
                    history.add(Map.of("role", "user", "content", displayText));
                }
            } else if (msg instanceof AiMessage ai && ai.text() != null && !ai.text().isBlank()) {
                history.add(Map.of("role", "agent", "content", ai.text()));
            }
        }

        return ResponseEntity.ok(Map.of("sessionId", sessionId, "messages", history));
    }

    /**
     * Delete chat history for a session (used by "New Chat").
     */
    @DeleteMapping("/chat-history")
    public ResponseEntity<Map<String, String>> clearChatHistory(@RequestParam String sessionId) {
        chatMemoryStore.deleteMessages(sessionId);
        log.info("Chat history cleared for session {}", sessionId);
        return ResponseEntity.ok(Map.of("status", "cleared", "sessionId", sessionId));
    }

    @PostMapping("/orchestrate")
    public ResponseEntity<Map<String, String>> orchestrateRequest(@RequestBody AgentRequest request) {
        log.info("Received PaSS intent for session {} (user={}): {}", request.getSessionId(), request.getUserId(), request.getUserIntent());
        long startTime = System.currentTimeMillis();

        try {
            String agentReply = orchestratorAgent.orchestrateSwitch(
                    request.getSessionId(), request.getUserId(), request.getUserIntent());
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
            log.error("❌ Session {}: Sync orchestration failed", request.getSessionId(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("reply", SseEmitterHelper.contextualErrorMessage(ex)));
        }
    }

    @PostMapping("/orchestrate-stream")
    public SseEmitter orchestrateStreamingRequest(@RequestBody AgentRequest request) throws IOException {
        log.info("Received streaming PaSS intent for session {} (user={}): {}", request.getSessionId(), request.getUserId(), request.getUserIntent());

        SseEmitter emitter = new SseEmitter(900000L);
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
                // Stored as a reference so we can interrupt it for immediate cleanup.
                AtomicBoolean heartbeatDone = new AtomicBoolean(false);
                Thread heartbeatThread = Thread.ofVirtual().name("heartbeat-" + request.getSessionId()).start(() -> {
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

                // Capture step-level token metrics from orchestration stage events
                AtomicInteger step1InputTokens  = new AtomicInteger(0);
                AtomicInteger step1OutputTokens = new AtomicInteger(0);
                AtomicBoolean didFormat         = new AtomicBoolean(false);

                // Get the token stream from the two-fold streaming orchestrator,
                // passing a stage callback that emits SSE "stage" events to the UI.
                TokenStream tokenStream = orchestratorAgent.orchestrateSwitchStreaming(
                        request.getSessionId(), request.getUserId(), request.getUserIntent(),
                        (stageName, stageData) -> {
                            if (emitterCompleted.get()) return;
                            try {
                                // Extract step1 token counts from the "classified" stage
                                if ("classified".equals(stageName)) {
                                    Object in  = stageData.get("step1InputTokens");
                                    Object out = stageData.get("step1OutputTokens");
                                    if (in  instanceof Number n) step1InputTokens.set(n.intValue());
                                    if (out instanceof Number n) step1OutputTokens.set(n.intValue());
                                }
                                // Track whether a formatting step was emitted
                                if ("formatting".equals(stageName)) {
                                    didFormat.set(true);
                                }

                                Map<String, Object> payload = new LinkedHashMap<>(stageData);
                                payload.put("stage", stageName);
                                SseEmitter.SseEventBuilder stageEvent = SseEmitter.event()
                                        .id(request.getSessionId())
                                        .name("stage")
                                        .data(objectMapper.writeValueAsString(payload))
                                        .reconnectTime(1000);
                                trySendEvent(emitter, stageEvent, request.getSessionId(), emitterCompleted);
                                log.debug("Session {}: SSE stage event → {}", request.getSessionId(), stageName);
                            } catch (Exception e) {
                                log.warn("Session {}: Failed to send stage event '{}'", request.getSessionId(), stageName, e);
                            }
                        });

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
                                heartbeatDone.set(true);
                                heartbeatThread.interrupt(); // wake from sleep immediately
                                long elapsedMs = System.currentTimeMillis() - startTime;

                                // Step 1 (classify) tokens — captured from "classified" stage event
                                int s1In  = step1InputTokens.get();
                                int s1Out = step1OutputTokens.get();

                                // Step 3 (format/stream) tokens — from LangChain4j TokenUsage
                                // Only attribute to the formatting step when deterministic formatting was used.
                                TokenUsage tu = response.tokenUsage();
                                int s3In  = (tu != null && tu.inputTokenCount()  != null) ? tu.inputTokenCount()  : 0;
                                int s3Out = (tu != null && tu.outputTokenCount() != null) ? tu.outputTokenCount() : 0;

                                // Combined totals (always include LLM tokens regardless of path)
                                int totalInput  = s1In + s3In;
                                int totalOutput = s1Out + s3Out;
                                int totalTokens = totalInput + totalOutput;

                                // Record combined metrics for ops dashboard
                                ollamaMetrics.record(totalOutput, totalInput, elapsedMs);

                                Map<String, Object> completeData = new LinkedHashMap<>();
                                completeData.put("elapsedMs", elapsedMs);
                                completeData.put("step1InputTokens", s1In);
                                completeData.put("step1OutputTokens", s1Out);
                                // Only include step3 (formatting) tokens when the formatting step actually ran
                                if (didFormat.get()) {
                                    completeData.put("step3InputTokens", s3In);
                                    completeData.put("step3OutputTokens", s3Out);
                                }
                                completeData.put("inputTokens", totalInput);
                                completeData.put("outputTokens", totalOutput);
                                completeData.put("totalTokens", totalTokens);
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
                                    orchestratorAgent.submitTemporalArchive(
                                            request.getSessionId(), request.getUserIntent(), reply);
                                }
                            } catch (Exception e) {
                                log.warn("Session {}: Error sending complete event", request.getSessionId(), e);
                            } finally {
                                orchestratorAgent.cleanupStreamingSession(request.getSessionId());
                            }
                        })
                        .onError(error -> {
                            heartbeatDone.set(true);
                            heartbeatThread.interrupt(); // wake from sleep immediately
                            orchestratorAgent.cleanupStreamingSession(request.getSessionId());
                            log.error("Session {}: Streaming error from LLM", request.getSessionId(), error);
                            auditLoggingService.logErrorEvent(
                                    "STREAMING_ORCHESTRATION_FAILED",
                                    request.getSessionId(),
                                    "Streaming orchestration failed for user intent: " + request.getUserIntent(),
                                    error
                            );
                            sendErrorEventAndComplete(emitter, error,
                                    request.getSessionId(), emitterCompleted);
                        })
                        .start();

            } catch (Exception e) {
                orchestratorAgent.cleanupStreamingSession(request.getSessionId());
                log.error("Session {}: Streaming setup error", request.getSessionId(), e);
                auditLoggingService.logErrorEvent(
                        "STREAMING_SETUP_FAILED",
                        request.getSessionId(),
                        "Failed to initialize streaming for user intent: " + request.getUserIntent(),
                        e
                );
                sendErrorEventAndComplete(emitter, e, request.getSessionId(), emitterCompleted);
            }
        });

        return emitter;
    }
}

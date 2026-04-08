package com.ayedata.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Utility for safely sending SSE events and completing emitters.
 * Handles the common patterns of checking emitter state, catching I/O errors,
 * and preventing duplicate completion calls.
 */
public final class SseEmitterHelper {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterHelper.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private SseEmitterHelper() {}

    /**
     * Classify a throwable into a short, user-facing message.
     * Never surfaces raw stack traces or internal class names to the client.
     */
    public static String contextualErrorMessage(Throwable error) {
        if (error == null) return "Something went wrong. Please try again.";
        String msg = error.getMessage() != null ? error.getMessage().toLowerCase() : "";
        String type = error.getClass().getSimpleName().toLowerCase();

        if (msg.contains("timeout") || msg.contains("timed out") || type.contains("timeout")) {
            return "The AI took too long to respond. Please simplify your request or try again in a moment.";
        }
        if (msg.contains("connection refused") || msg.contains("connect") || msg.contains("econnrefused")) {
            return "The AI engine is temporarily unreachable. Please wait a moment and try again.";
        }
        if (msg.contains("context length") || msg.contains("token") || msg.contains("too long")) {
            return "Your request was too long for the AI to process. Please shorten your message and try again.";
        }
        if (msg.contains("model") || msg.contains("ollama") || msg.contains("not found")) {
            return "The AI model is not available right now. Please try again shortly.";
        }
        if (msg.contains("out of memory") || msg.contains("oom")) {
            return "The AI engine ran out of resources. Please try a shorter request.";
        }
        return "I was unable to process your request due to an internal error. " +
               "Please rephrase your request and try again. If the issue persists, use the 'Speak to Human' option.";
    }

    /**
     * Send a structured SSE error event (name=\"error\") with a user-friendly message,
     * then complete the emitter normally (not with error) so the client receives the event.
     */
    public static void sendErrorEventAndComplete(SseEmitter emitter, Throwable error,
                                                  String sessionId, AtomicBoolean emitterCompleted) {
        if (emitterCompleted.getAndSet(true)) return;
        try {
            Map<String, Object> errorData = new LinkedHashMap<>();
            errorData.put("message", contextualErrorMessage(error));
            errorData.put("retryable", true);
            SseEmitter.SseEventBuilder errorEvent = SseEmitter.event()
                    .id(sessionId)
                    .name("error")
                    .data(objectMapper.writeValueAsString(errorData))
                    .reconnectTime(3000);
            emitter.send(errorEvent);
            emitter.complete();
        } catch (Exception e) {
            log.warn("Session {}: could not send error event to client", sessionId, e);
            try { emitter.complete(); } catch (Exception ignored) {}
        }
    }

    public static boolean trySendEvent(SseEmitter emitter, SseEmitter.SseEventBuilder event,
                                       String sessionId, AtomicBoolean emitterCompleted) {
        try {
            if (emitterCompleted.get()) {
                log.debug("Session {}: Emitter already completed, skipping send", sessionId);
                return false;
            }
            emitter.send(event);
            return true;
        } catch (IllegalStateException e) {
            log.warn("Session {}: Emitter already completed (state error)", sessionId);
            emitterCompleted.set(true);
            return false;
        } catch (IOException e) {
            log.warn("Session {}: Client disconnected during send", sessionId);
            emitterCompleted.set(true);
            return false;
        } catch (Exception e) {
            log.error("Session {}: Unexpected error sending event", sessionId, e);
            emitterCompleted.set(true);
            return false;
        }
    }

    public static void tryCompleteEmitter(SseEmitter emitter, String sessionId,
                                          AtomicBoolean emitterCompleted) {
        if (emitterCompleted.getAndSet(true)) {
            log.debug("Session {}: Emitter already completed, skipping complete()", sessionId);
            return;
        }
        try {
            emitter.complete();
        } catch (Exception e) {
            log.warn("Session {}: Error completing emitter", sessionId, e);
        }
    }

    public static void tryCompleteEmitterWithError(SseEmitter emitter, Exception error,
                                                   String sessionId, AtomicBoolean emitterCompleted) {
        if (emitterCompleted.getAndSet(true)) {
            log.debug("Session {}: Emitter already completed, skipping completeWithError()", sessionId);
            return;
        }
        try {
            emitter.completeWithError(error);
        } catch (Exception e) {
            log.warn("Session {}: Error completing emitter with error", sessionId, e);
        }
    }
}

package com.ayedata.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Utility for safely sending SSE events and completing emitters.
 * Handles the common patterns of checking emitter state, catching I/O errors,
 * and preventing duplicate completion calls.
 */
public final class SseEmitterHelper {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterHelper.class);

    private SseEmitterHelper() {}

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

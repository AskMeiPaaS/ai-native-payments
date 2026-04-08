package com.ayedata.audit.exception;

import com.ayedata.audit.service.AuditLoggingService;
import com.ayedata.controller.SseEmitterHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalAuditExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalAuditExceptionHandler.class);
    private final AuditLoggingService auditLoggingService;

    public GlobalAuditExceptionHandler(AuditLoggingService auditLoggingService) {
        this.auditLoggingService = auditLoggingService;
    }

    /**
     * Silently ignore client-disconnect exceptions from Tomcat.
     * These fire when an SSE client closes the browser tab mid-stream — they are
     * normal and must NOT be logged as system exceptions or trigger a response write
     * (the connection is already gone).
     */
    @ExceptionHandler({ org.apache.catalina.connector.ClientAbortException.class })
    public void handleClientAbort(Exception ex) {
        log.debug("SSE client disconnected: {}", ex.getMessage());
        // No response — client is gone
    }

    /**
     * Return a plain 404 for unmapped paths without audit noise.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResourceFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "NOT_FOUND", "path", ex.getResourcePath()));
    }

    /**
     * Catch-all handler. Skips response write if the connection is an SSE stream
     * (Content-Type: text/event-stream) to avoid HttpMessageNotWritableException —
     * there is no converter for Map → text/event-stream.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleAllUncaughtExceptions(
            Exception ex, HttpServletRequest request, HttpServletResponse response) {

        // Client-abort wrapped in a generic IOException — treat the same as above
        if (ex instanceof IOException && ex.getMessage() != null
                && ex.getMessage().toLowerCase().contains("broken pipe")) {
            log.debug("Broken pipe (client disconnected): {}", ex.getMessage());
            return null;
        }

        // If the response is already committed or this is an SSE endpoint, don't
        // attempt to write a JSON body — it will fail with HttpMessageNotWritableException
        String accept = request.getHeader("Accept");
        boolean isSseRequest = accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        if (response.isCommitted() || isSseRequest) {
            log.debug("Exception on SSE/committed response (client disconnected): {}", ex.getMessage());
            return null;
        }

        String sessionId = request.getParameter("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = request.getHeader("X-Session-Id");
        }

        auditLoggingService.logErrorEvent("UNHANDLED_SYSTEM_EXCEPTION", sessionId, ex.getMessage(), ex);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "ERROR");
        body.put("message", SseEmitterHelper.contextualErrorMessage(ex));
        body.put("retryable", "true");

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
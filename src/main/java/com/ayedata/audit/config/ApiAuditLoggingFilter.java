package com.ayedata.audit.config;

import com.ayedata.audit.service.AuditLoggingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures request/response pairs for API endpoints and stores them in pass_audit.
 * Chat endpoints are logged in PaSSController so we skip them here to avoid duplication
 * and to keep streaming SSE responses untouched.
 */
@Component
public class ApiAuditLoggingFilter extends OncePerRequestFilter {

    private static final Pattern SESSION_PATH_PATTERN = Pattern.compile("/session/([^/?]+)");

    private final AuditLoggingService auditLoggingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiAuditLoggingFilter(AuditLoggingService auditLoggingService) {
        this.auditLoggingService = auditLoggingService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/v1/")
                || uri.startsWith("/api/v1/agent/orchestrate")
                || uri.equals("/api/v1/agent/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, 16_384);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        long startTime = System.currentTimeMillis();

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            long latencyMs = System.currentTimeMillis() - startTime;
            String requestPayload = readPayload(wrappedRequest.getContentAsByteArray(), wrappedRequest.getCharacterEncoding());
            String responsePayload = readPayload(wrappedResponse.getContentAsByteArray(), wrappedResponse.getCharacterEncoding());
            String sessionId = extractSessionId(wrappedRequest, requestPayload, responsePayload);

            auditLoggingService.logApiInteraction(
                    sessionId,
                    request.getRequestURI(),
                    request.getMethod(),
                    wrappedResponse.getStatus(),
                    requestPayload,
                    responsePayload,
                    latencyMs
            );

            wrappedResponse.copyBodyToResponse();
        }
    }

    private String extractSessionId(HttpServletRequest request, String requestPayload, String responsePayload) {
        // Try header first
        String headerSessionId = request.getHeader("X-Session-Id");
        if (StringUtils.hasText(headerSessionId) && isValidSessionId(headerSessionId)) {
            return headerSessionId;
        }

        // Try request parameter
        String paramSessionId = request.getParameter("sessionId");
        if (StringUtils.hasText(paramSessionId) && isValidSessionId(paramSessionId)) {
            return paramSessionId;
        }

        // Try request body
        String fromRequestBody = extractFieldFromJson(requestPayload, "sessionId");
        if (StringUtils.hasText(fromRequestBody) && isValidSessionId(fromRequestBody)) {
            return fromRequestBody;
        }

        // Try response body
        String fromResponseBody = extractFieldFromJson(responsePayload, "sessionId");
        if (StringUtils.hasText(fromResponseBody) && isValidSessionId(fromResponseBody)) {
            return fromResponseBody;
        }

        // Try URI path pattern
        Matcher matcher = SESSION_PATH_PATTERN.matcher(request.getRequestURI());
        if (matcher.find()) {
            String pathSessionId = matcher.group(1);
            if (isValidSessionId(pathSessionId)) {
                return pathSessionId;
            }
        }

        return "NO_SESSION";
    }

    /**
     * Validates sessionId format to prevent injection attacks.
     * Allows: alphanumeric characters, hyphens, underscores, dots
     * Max length: 255 characters
     */
    private boolean isValidSessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty() || sessionId.length() > 255) {
            return false;
        }
        // Allow UUID format, base64, and timestamps with separators
        return sessionId.matches("^[a-zA-Z0-9\\-_.]+$");
    }

    private String extractFieldFromJson(String payload, String fieldName) {
        if (!StringUtils.hasText(payload) || !payload.trim().startsWith("{")) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode field = root.get(fieldName);
            if (field != null && !field.isNull() && StringUtils.hasText(field.asText())) {
                return field.asText();
            }
        } catch (Exception ignored) {
            // Non-JSON payloads are ignored intentionally.
        }

        return null;
    }

    private String readPayload(byte[] content, String encoding) {
        if (content == null || content.length == 0) {
            return "";
        }

        Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        return new String(content, charset);
    }
}

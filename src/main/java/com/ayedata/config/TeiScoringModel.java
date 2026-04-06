package com.ayedata.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Custom LangChain4j ScoringModel for HuggingFace Text Embeddings Inference (TEI).
 * 
 * Uses BAAI bge-m3-reranker-v2 model for high-fidelity semantic similarity scoring.
 * This reranker is deployed locally via Docker (tei-reranker service on port 8081).
 * It refines vector search results by re-scoring them with neural network embeddings,
 * providing better ranking accuracy for the fraud detection and user behavior analysis.
 * 
 * IMPROVEMENTS:
 * - ✅ HttpClient is injected (not created) for proper lifecycle management
 * - ✅ Jackson-based JSON parsing (safe, reliable)
 * - ✅ Timeout-aware request handling with specific exception catching
 * - ✅ Comprehensive error logging for debugging
 * - ✅ Virtual thread compatible (uses shared HttpClient)
 */
public class TeiScoringModel implements ScoringModel {
    private static final Logger log = LoggerFactory.getLogger(TeiScoringModel.class);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Integer requestTimeoutSeconds;

    private TeiScoringModel(String baseUrl, String modelName, HttpClient httpClient, Integer requestTimeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = httpClient;  // ✅ Injected (shared singleton)
        this.objectMapper = new ObjectMapper();
        this.requestTimeoutSeconds = requestTimeoutSeconds != null ? requestTimeoutSeconds : 600;
    }

    // Manual builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseUrl;
        private String modelName;
        private HttpClient httpClient;
        private Integer requestTimeoutSeconds;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder requestTimeoutSeconds(Integer requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
            return this;
        }

        public TeiScoringModel build() {
            return new TeiScoringModel(baseUrl, modelName, httpClient, requestTimeoutSeconds);
        }
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> texts, String query) {
        try {
            // ✅ Convert TextSegment objects to plain strings for TEI API
            List<String> textStrings = texts.stream()
                    .map(TextSegment::text)
                    .collect(Collectors.toList());
            
            // ✅ Safe JSON construction using Jackson
            Map<String, Object> requestBody = Map.of(
                "query", query,
                "texts", textStrings
            );
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/rerank"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))  // ✅ Request-level timeout
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            log.debug("Sending rerank request to TEI at: {} with timeout: {}s", baseUrl, requestTimeoutSeconds);
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                log.error("TEI Reranker error [status={}]: {}", response.statusCode(), response.body());
                throw new TeiRerankerException("TEI returned HTTP " + response.statusCode(), response.statusCode());
            }

            // ✅ Safe JSON parsing using Jackson
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> scoreResponse = objectMapper.readValue(response.body(), List.class);
            
            List<Double> scores = scoreResponse.stream()
                    .map(m -> {
                        Object scoreObj = m.get("score");
                        if (scoreObj instanceof Number) {
                            return ((Number) scoreObj).doubleValue();
                        }
                        return 0.0;
                    })
                    .collect(Collectors.toList());

            log.debug("Successfully scored {} documents with reranker", textStrings.size());
            return Response.from(scores);
            
        } catch (HttpConnectTimeoutException e) {
            log.warn("Cannot connect to TEI reranker at {}. Ensure tei-reranker service is running.", baseUrl);
            throw new TeiRerankerException("Connection timeout", 503);
        } catch (HttpTimeoutException e) {
            log.warn("TEI reranker request timeout after {}s. Endpoint: {}", requestTimeoutSeconds, baseUrl);
            throw new TeiRerankerException("Request timeout", 408);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("TEI reranking request was interrupted", e);
            throw new TeiRerankerException("Request interrupted", 500);
        } catch (Exception e) {
            log.error("Unexpected error in TEI reranking", e);
            throw new TeiRerankerException("Unexpected error: " + e.getMessage(), 500);
        }
    }

    /**
     * Custom exception for TEI reranker errors with HTTP status code.
     */
    public static class TeiRerankerException extends RuntimeException {
        private final int httpStatus;

        public TeiRerankerException(String message, int httpStatus) {
            super(message);
            this.httpStatus = httpStatus;
        }

        public int getHttpStatus() {
            return httpStatus;
        }
    }
}


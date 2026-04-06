package com.ayedata.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Custom LangChain4j ScoringModel for Voyage AI Reranker API.
 * 
 * Uses Voyage AI rerank-lite-1 model for high-fidelity semantic similarity scoring.
 * This reranker refines vector search results by re-scoring them with neural network embeddings,
 * providing better ranking accuracy for fraud detection and user behavior analysis.
 * 
 * IMPROVEMENTS:
 * - ✅ HttpClient is injected (not created) for proper lifecycle management
 * - ✅ Jackson-based JSON parsing with TypeReference for type-safe deserialization
 * - ✅ Timeout-aware request handling with specific exception catching
 * - ✅ Comprehensive error logging for debugging
 * - ✅ Virtual thread compatible (uses shared HttpClient)
 * - ✅ Direct API integration with Voyage AI official endpoint
 */
public class VoyageAiScoringModel implements ScoringModel {
    private static final Logger log = LoggerFactory.getLogger(VoyageAiScoringModel.class);
    private static final String VOYAGE_API_ENDPOINT = "https://api.voyageai.com/v1/rerank";

    private final String apiKey;
    private final String modelName;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Integer requestTimeoutSeconds;
    private final Integer rerankerTopK;

    private VoyageAiScoringModel(String apiKey, String modelName, HttpClient httpClient,
                                 Integer requestTimeoutSeconds, Integer rerankerTopK) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Voyage AI API key is required");
        }
        this.apiKey = apiKey;
        this.modelName = modelName != null ? modelName : "rerank-lite-1";
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.requestTimeoutSeconds = requestTimeoutSeconds != null ? requestTimeoutSeconds : 30;
        this.rerankerTopK = rerankerTopK;
    }

    // Manual builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiKey;
        private String modelName;
        private HttpClient httpClient;
        private Integer requestTimeoutSeconds;
        private Integer rerankerTopK;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
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

        public Builder rerankerTopK(Integer rerankerTopK) {
            this.rerankerTopK = rerankerTopK;
            return this;
        }

        public VoyageAiScoringModel build() {
            return new VoyageAiScoringModel(apiKey, modelName, httpClient, requestTimeoutSeconds, rerankerTopK);
        }
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> texts, String query) {
        try {
            List<String> textStrings = texts.stream()
                    .map(TextSegment::text)
                    .collect(Collectors.toList());

            int effectiveTopK = (rerankerTopK != null && rerankerTopK > 0)
                    ? Math.min(rerankerTopK, textStrings.size())
                    : textStrings.size();

            Map<String, Object> requestBody = Map.of(
                "model", modelName,
                "query", query,
                "documents", textStrings,
                "top_k", effectiveTopK
            );

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.debug("📤 Reranking {} docs, top_k={}", textStrings.size(), effectiveTopK);

            // ✅ Create HTTP request with proper headers and timeout
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(VOYAGE_API_ENDPOINT))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            // ✅ Execute request with timeout handling
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // ✅ Handle non-200 responses
            if (response.statusCode() != 200) {
                log.error("❌ Voyage AI API error: HTTP {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("Voyage AI API returned status " + response.statusCode());
            }

            // ✅ Type-safe deserialization using TypeReference
            Map<String, Object> responseBody = objectMapper.readValue(
                    response.body(),
                    new TypeReference<Map<String, Object>>() {}
            );
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) responseBody.get("data");

            // Fill scores array — 0.0 for indices not returned by top_k
            Double[] allScores = new Double[textStrings.size()];
            Arrays.fill(allScores, 0.0);
            for (Map<String, Object> item : data) {
                int idx = ((Number) item.get("index")).intValue();
                allScores[idx] = ((Number) item.get("relevance_score")).doubleValue();
            }

            log.debug("✅ Reranked: {} scored out of {} candidates", data.size(), textStrings.size());
            return Response.from(Arrays.asList(allScores));

        } catch (HttpConnectTimeoutException e) {
            log.error("⏱️ Connection timeout to Voyage AI: {}", e.getMessage());
            throw new RuntimeException("Connection timeout to Voyage AI API", e);
        } catch (HttpTimeoutException e) {
            log.error("⏱️ Request timeout to Voyage AI: {}", e.getMessage());
            throw new RuntimeException("Request timeout to Voyage AI API", e);
        } catch (Exception e) {
            log.error("❌ Error calling Voyage AI reranker: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to rerank documents with Voyage AI", e);
        }
    }
}
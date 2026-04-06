package com.ayedata.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Custom LangChain4j EmbeddingModel for Voyage AI Embeddings API.
 * 
 * Uses Voyage AI voyage-4 model for high-quality semantic embeddings (1024 dimensions).
 * This embedding model converts text (behavioral telemetry, user queries, mandate descriptions)
 * into dense vectors for semantic similarity search and fraud detection baselines.
 * 
 * FEATURES:
 * - ✅ HttpClient is injected (not created) for proper lifecycle management
 * - ✅ Jackson-based JSON parsing (safe, reliable)
 * - ✅ Timeout-aware request handling with specific exception catching
 * - ✅ Comprehensive error logging for debugging
 * - ✅ Virtual thread compatible (uses shared HttpClient)
 * - ✅ Direct API integration with Voyage AI official endpoint (https://api.voyageai.com/v1/embeddings)
 * - ✅ Supports batch embeddings (multiple texts in single request)
 * - ✅ Returns 1024-dimensional embeddings for voyage-4 model
 */
public class VoyageAiEmbeddingModelImpl implements EmbeddingModel {
    private static final Logger log = LoggerFactory.getLogger(VoyageAiEmbeddingModelImpl.class);
    private static final String VOYAGE_API_ENDPOINT = "https://api.voyageai.com/v1/embeddings";

    private final String apiKey;
    private final String modelName;
    private final Integer dimension;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Integer requestTimeoutSeconds;

    private VoyageAiEmbeddingModelImpl(String apiKey, String modelName, Integer dimension, HttpClient httpClient, Integer requestTimeoutSeconds) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Voyage AI API key is required");
        }
        this.apiKey = apiKey;
        this.modelName = modelName != null ? modelName : "voyage-4";
        this.dimension = dimension != null ? dimension : 1024;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.requestTimeoutSeconds = requestTimeoutSeconds != null ? requestTimeoutSeconds : 30;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiKey;
        private String modelName;
        private Integer dimension;
        private HttpClient httpClient;
        private Integer requestTimeoutSeconds;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder dimension(Integer dimension) {
            this.dimension = dimension;
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

        public VoyageAiEmbeddingModelImpl build() {
            return new VoyageAiEmbeddingModelImpl(apiKey, modelName, dimension, httpClient, requestTimeoutSeconds);
        }
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        // Convert TextSegment to strings for API call
        List<String> texts = segments.stream()
                .map(TextSegment::text)
                .collect(Collectors.toList());
        return embedAllStrings(texts);
    }

    private Response<List<Embedding>> embedAllStrings(List<String> texts) {
        try {
            log.info("Embedding {} texts using Voyage AI model: {}", texts.size(), modelName);

            // ✅ Build request payload
            Map<String, Object> requestPayload = Map.of(
                    "model", modelName,
                    "input", texts
            );

            String jsonBody = objectMapper.writeValueAsString(requestPayload);

            // ✅ Create HTTP POST request with Bearer token authentication
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(VOYAGE_API_ENDPOINT))
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            // ✅ Execute request with timeout handling
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Voyage AI API error: HTTP {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("Voyage AI API returned status " + response.statusCode() + ": " + response.body());
            }

            // ✅ Parse response and extract embeddings
            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = objectMapper.readValue(response.body(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> dataList = (List<Map<String, Object>>) responseMap.get("data");

            List<Embedding> embeddings = new ArrayList<>();
            for (Map<String, Object> dataItem : dataList) {
                @SuppressWarnings("unchecked")
                List<Double> embeddingList = (List<Double>) dataItem.get("embedding");
                float[] embeddingArray = new float[embeddingList.size()];
                for (int i = 0; i < embeddingList.size(); i++) {
                    embeddingArray[i] = embeddingList.get(i).floatValue();
                }
                embeddings.add(new Embedding(embeddingArray));
            }

            log.info("Successfully embedded {} texts into {}D vectors", texts.size(), dimension);
            return Response.from(embeddings);

        } catch (HttpTimeoutException e) {
            log.error("Voyage AI API timeout after {}s: {}", requestTimeoutSeconds, e.getMessage());
            throw new RuntimeException("Voyage AI API timeout", e);
        } catch (Exception e) {
            log.error("Failed to embed texts with Voyage AI", e);
            throw new RuntimeException("Voyage AI embedding failed", e);
        }
    }

    @Override
    public Response<Embedding> embed(String text) {
        Response<List<Embedding>> response = embedAllStrings(List.of(text));
        return Response.from(response.content().get(0));
    }

    @Override
    public Response<Embedding> embed(TextSegment segment) {
        return embed(segment.text());
    }

    @Override
    public int dimension() {
        return dimension;
    }
}
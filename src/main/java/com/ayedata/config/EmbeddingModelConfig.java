package com.ayedata.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

/**
 * Voyage AI embedding model bean (voyage-4, 1024 dimensions).
 */
@Configuration
public class EmbeddingModelConfig {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelConfig.class);

    @Value("${app.ai.embedding.api-key}")
    private String voyageApiKey;

    @Value("${app.ai.embedding.model-name}")
    private String embeddingModelName;

    @Value("${app.ai.http.request-timeout-seconds:900}")
    private Integer requestTimeout;

    @Bean
    public EmbeddingModel embeddingModel(HttpClient sharedHttpClient) {
        log.info("Creating Voyage AI EmbeddingModel: {}", embeddingModelName);
        return VoyageAiEmbeddingModelImpl.builder()
                .apiKey(voyageApiKey)
                .modelName(embeddingModelName)
                .dimension(1024)
                .httpClient(sharedHttpClient)
                .requestTimeoutSeconds(requestTimeout)
                .build();
    }
}

package com.ayedata.config;

import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.model.voyageai.VoyageAiScoringModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Voyage AI reranker/scoring model bean (rerank-lite-1).
 * Uses official LangChain4j Voyage AI integration.
 */
@Configuration
public class ScoringModelConfig {
    private static final Logger log = LoggerFactory.getLogger(ScoringModelConfig.class);

    @Value("${app.ai.reranker.api-key}")
    private String rerankerApiKey;

    @Value("${app.ai.reranker.model-name}")
    private String rerankerModelName;

    @Value("${app.ai.reranker.top-k:2}")
    private Integer rerankerTopK;

    @Value("${app.ai.http.request-timeout-seconds:900}")
    private Integer requestTimeout;

    @Bean
    public ScoringModel scoringModel() {
        log.info("Creating Voyage AI ScoringModel: {} (top_k={})", rerankerModelName, rerankerTopK);
        return VoyageAiScoringModel.builder()
                .apiKey(rerankerApiKey)
                .modelName(rerankerModelName)
                .timeout(Duration.ofSeconds(requestTimeout))
                .topK(rerankerTopK)
                .build();
    }
}

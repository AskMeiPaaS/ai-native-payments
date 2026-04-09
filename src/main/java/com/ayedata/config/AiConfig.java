package com.ayedata.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

/**
 * AI configuration startup diagnostics.
 * Bean definitions are in dedicated config classes:
 * @see EmbeddingModelConfig
 * @see ScoringModelConfig
 * @see LlmModelConfig
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Value("${app.ai.embedding.api-key}")
    private String voyageApiKey;

    @Value("${app.ai.embedding.model-name}")
    private String embeddingModelName;

    @Value("${app.ai.reranker.api-key}")
    private String rerankerApiKey;

    @Value("${app.ai.reranker.model-name}")
    private String rerankerModelName;

    @Value("${app.ai.llm.base-url}")
    private String llmBaseUrl;

    @Value("${app.ai.llm.model-name}")
    private String llmModelName;

    @Value("${app.ai.llm.temperature}")
    private Double llmTemperature;

    @Value("${app.ai.llm.timeout-seconds:900}")
    private Integer llmTimeoutSeconds;

    @PostConstruct
    public void logConfiguration() {
        log.info("═════════════════════════════════════════════════════════════");
        log.info("🧠 AI CONFIGURATION LOADED:");
        log.info("   Embedding Model: {}", embeddingModelName);
        log.info("   Embedding API Key: {}", voyageApiKey != null && !voyageApiKey.isEmpty() ? "✅ SET" : "❌ NOT SET");
        log.info("   Reranker Model: {}", rerankerModelName);
        log.info("   Reranker API Key: {}", rerankerApiKey != null && !rerankerApiKey.isEmpty() ? "✅ SET" : "❌ NOT SET");
        log.info("   LLM Base URL: {}", llmBaseUrl);
        log.info("   LLM Model: {}", llmModelName);
        log.info("   LLM Temperature: {}", llmTemperature);
        log.info("   LLM Timeout: {} seconds", llmTimeoutSeconds);
        log.info("═════════════════════════════════════════════════════════════");
    }
}
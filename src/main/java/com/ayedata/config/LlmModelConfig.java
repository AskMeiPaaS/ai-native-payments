package com.ayedata.config;

import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Ollama LLM model bean for the Supervisor Agent.
 */
@Configuration
public class LlmModelConfig {
    private static final Logger log = LoggerFactory.getLogger(LlmModelConfig.class);

    @Value("${app.ai.llm.base-url}")
    private String llmBaseUrl;

    @Value("${app.ai.llm.model-name}")
    private String llmModelName;

    @Value("${app.ai.llm.temperature}")
    private Double llmTemperature;

    @Value("${app.ai.llm.timeout-seconds:900}")
    private Integer llmTimeoutSeconds;

    @Value("${app.ai.llm.num-ctx:4096}")
    private Integer numCtx;

    @Value("${app.ai.llm.num-predict:1024}")
    private Integer numPredict;

    @Bean
    public OllamaChatModel chatLanguageModel() {
        log.info("Creating Ollama LLM: {} at {} (temp={}, timeout={}s, numCtx={}, numPredict={})",
                llmModelName, llmBaseUrl, llmTemperature, llmTimeoutSeconds, numCtx, numPredict);
        return OllamaChatModel.builder()
                .baseUrl(llmBaseUrl)
                .modelName(llmModelName)
                .temperature(llmTemperature)
                .timeout(Duration.ofSeconds(llmTimeoutSeconds))
                .numCtx(numCtx)
                .numPredict(numPredict)
                .build();
    }

    @Bean
    public OllamaStreamingChatModel streamingChatLanguageModel() {
        log.info("Creating Ollama Streaming LLM: {} at {} (temp={}, timeout={}s, numCtx={}, numPredict={})",
                llmModelName, llmBaseUrl, llmTemperature, llmTimeoutSeconds, numCtx, numPredict);
        return OllamaStreamingChatModel.builder()
                .baseUrl(llmBaseUrl)
                .modelName(llmModelName)
                .temperature(llmTemperature)
                .timeout(Duration.ofSeconds(llmTimeoutSeconds))
                .numCtx(numCtx)
                .numPredict(numPredict)
                .build();
    }
}

package com.ayedata.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * Shared HTTP client for AI API calls (Voyage AI embedding + reranking).
 * Uses virtual threads for non-blocking I/O.
 */
@Configuration
public class HttpClientConfig {
    private static final Logger log = LoggerFactory.getLogger(HttpClientConfig.class);

    @Value("${app.ai.http.connect-timeout-seconds:10}")
    private Integer connectTimeout;

    @Bean
    public HttpClient sharedHttpClient() {
        log.info("Creating shared HttpClient (connect timeout: {}s, virtual threads)", connectTimeout);
        return HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .version(HttpClient.Version.HTTP_2)
                .build();
    }
}

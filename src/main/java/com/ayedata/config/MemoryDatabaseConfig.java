package com.ayedata.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

@Configuration
public class MemoryDatabaseConfig {
    private static final Logger log = LoggerFactory.getLogger(MemoryDatabaseConfig.class);

    @Value("${spring.data.mongodb.memory.uri}")
    private String memoryUri;

    @Bean(name = "memoryMongoClient")
    public MongoClient memoryMongoClient() {
        log.info("Initializing Memory MongoDB Client...");
        return MongoClients.create(memoryUri);
    }

    @Bean(name = "memoryMongoTemplate")
    public MongoTemplate memoryMongoTemplate(
            @Qualifier("memoryMongoClient") MongoClient memoryMongoClient) {
        log.info("Creating Memory MongoTemplate");
        return new MongoTemplate(new SimpleMongoClientDatabaseFactory(memoryMongoClient, "pass_memory"));
    }
}

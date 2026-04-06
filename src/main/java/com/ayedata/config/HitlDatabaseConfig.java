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
public class HitlDatabaseConfig {
    private static final Logger log = LoggerFactory.getLogger(HitlDatabaseConfig.class);

    @Value("${spring.data.mongodb.hitl.uri}")
    private String hitlUri;

    @Bean(name = "hitlMongoClient")
    public MongoClient hitlMongoClient() {
        log.info("Initializing HITL MongoDB Client...");
        return MongoClients.create(hitlUri);
    }

    @Bean(name = "hitlMongoTemplate")
    public MongoTemplate hitlMongoTemplate(
            @Qualifier("hitlMongoClient") MongoClient hitlMongoClient) {
        log.info("Creating HITL MongoTemplate");
        return new MongoTemplate(new SimpleMongoClientDatabaseFactory(hitlMongoClient, "pass_hitl"));
    }
}

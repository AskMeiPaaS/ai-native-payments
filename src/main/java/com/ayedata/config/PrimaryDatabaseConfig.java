package com.ayedata.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

@Configuration
public class PrimaryDatabaseConfig {
    private static final Logger log = LoggerFactory.getLogger(PrimaryDatabaseConfig.class);

    @Value("${spring.data.mongodb.primary.uri}")
    private String primaryUri;

    @Bean(name = "primaryMongoClient")
    public MongoClient primaryMongoClient() {
        log.info("Initializing Primary MongoDB Client...");
        return MongoClients.create(primaryUri);
    }

    @Primary
    @Bean(name = "primaryMongoTemplate")
    public MongoTemplate primaryMongoTemplate(
            @Qualifier("primaryMongoClient") MongoClient primaryMongoClient) {
        log.info("Creating Primary MongoTemplate");
        return new MongoTemplate(new SimpleMongoClientDatabaseFactory(primaryMongoClient, "pass_main"));
    }
}

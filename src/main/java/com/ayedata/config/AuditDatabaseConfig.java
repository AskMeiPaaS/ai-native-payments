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
public class AuditDatabaseConfig {
    private static final Logger log = LoggerFactory.getLogger(AuditDatabaseConfig.class);

    @Value("${spring.data.mongodb.audit.uri}")
    private String auditUri;

    @Bean(name = "auditMongoClient")
    public MongoClient auditMongoClient() {
        log.info("Initializing Audit MongoDB Client...");
        return MongoClients.create(auditUri);
    }

    @Bean(name = "auditMongoTemplate")
    public MongoTemplate auditMongoTemplate(
            @Qualifier("auditMongoClient") MongoClient auditMongoClient) {
        log.info("Creating Audit MongoTemplate");
        return new MongoTemplate(new SimpleMongoClientDatabaseFactory(auditMongoClient, "pass_audit"));
    }
}

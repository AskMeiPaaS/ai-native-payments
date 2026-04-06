package com.ayedata.config;

import com.mongodb.client.MongoClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;

/**
 * Graceful shutdown handler for all MongoDB connections.
 * Individual database configs are in their own classes:
 * {@link PrimaryDatabaseConfig}, {@link AuditDatabaseConfig},
 * {@link HitlDatabaseConfig}, {@link MemoryDatabaseConfig}.
 */
@Configuration
public class MongoConfig {

    private static final Logger log = LoggerFactory.getLogger(MongoConfig.class);

    private final MongoClient primaryMongoClient;
    private final MongoClient auditMongoClient;
    private final MongoClient hitlMongoClient;
    private final MongoClient memoryMongoClient;

    public MongoConfig(
            @Qualifier("primaryMongoClient") MongoClient primaryMongoClient,
            @Qualifier("auditMongoClient") MongoClient auditMongoClient,
            @Qualifier("hitlMongoClient") MongoClient hitlMongoClient,
            @Qualifier("memoryMongoClient") MongoClient memoryMongoClient) {
        this.primaryMongoClient = primaryMongoClient;
        this.auditMongoClient = auditMongoClient;
        this.hitlMongoClient = hitlMongoClient;
        this.memoryMongoClient = memoryMongoClient;
    }

    @PreDestroy
    public void closeConnections() {
        log.info("Closing MongoDB connections on application shutdown...");
        closeQuietly(primaryMongoClient, "Primary");
        closeQuietly(auditMongoClient, "Audit");
        closeQuietly(hitlMongoClient, "HITL");
        closeQuietly(memoryMongoClient, "Memory");
    }

    private void closeQuietly(MongoClient client, String name) {
        if (client != null) {
            try {
                client.close();
                log.info("✅ {} MongoDB connection closed", name);
            } catch (Exception e) {
                log.error("❌ Error closing {} MongoDB client", name, e);
            }
        }
    }
}

package com.ayedata.init;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Validates MongoDB connectivity with retry logic.
 * Used by initializers to wait for databases to become available at startup.
 */
@Component
public class DatabaseConnectionValidator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionValidator.class);
    private static final int MAX_RETRIES = 15;
    private static final int RETRY_DELAY_MS = 2000;

    public Set<String> waitForDatabase(MongoTemplate template, String dbName) {
        for (int i = 1; i <= MAX_RETRIES; i++) {
            try {
                return template.getCollectionNames();
            } catch (Exception e) {
                if (i == MAX_RETRIES) {
                    log.error("❌ {} MongoDB did not become available after {} retries.", dbName, MAX_RETRIES);
                    throw e;
                }
                log.warn("⏳ {} MongoDB not yet available. Retrying in {}ms... ({}/{}) [{}]",
                        dbName, RETRY_DELAY_MS, i, MAX_RETRIES, e.getMessage());
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during " + dbName + " MongoDB initialization", ie);
                }
            }
        }
        throw new IllegalStateException("Max retries exceeded for " + dbName + " DB");
    }
}

package com.ayedata.init;

import com.ayedata.hitl.init.HitlDatabaseInitializer;
import com.ayedata.rag.init.RagKnowledgeSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Top-level database initializer that orchestrates all database setup steps.
 * Each concern is delegated to a focused initializer component.
 */
@Component
public class DatabaseInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final MongoTemplate primaryTemplate;
    private final DatabaseConnectionValidator connectionValidator;
    private final EncryptionKeyInitializer encryptionKeyInitializer;
    private final VectorSearchIndexInitializer vectorSearchIndexInitializer;
    private final HitlDatabaseInitializer hitlDatabaseInitializer;
    private final MemoryDatabaseInitializer memoryDatabaseInitializer;
    private final RagKnowledgeSeeder ragKnowledgeSeeder;
    private final UserProfileInitializer userProfileInitializer;

    public DatabaseInitializer(
            @Qualifier("primaryMongoTemplate") MongoTemplate primaryTemplate,
            DatabaseConnectionValidator connectionValidator,
            EncryptionKeyInitializer encryptionKeyInitializer,
            VectorSearchIndexInitializer vectorSearchIndexInitializer,
            HitlDatabaseInitializer hitlDatabaseInitializer,
            MemoryDatabaseInitializer memoryDatabaseInitializer,
            RagKnowledgeSeeder ragKnowledgeSeeder,
            UserProfileInitializer userProfileInitializer) {
        this.primaryTemplate = primaryTemplate;
        this.connectionValidator = connectionValidator;
        this.encryptionKeyInitializer = encryptionKeyInitializer;
        this.vectorSearchIndexInitializer = vectorSearchIndexInitializer;
        this.hitlDatabaseInitializer = hitlDatabaseInitializer;
        this.memoryDatabaseInitializer = memoryDatabaseInitializer;
        this.ragKnowledgeSeeder = ragKnowledgeSeeder;
        this.userProfileInitializer = userProfileInitializer;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("🚀 Running Autonomous Database Initialization...");

        // 0. Wait for primary DB and create core collections
        Set<String> existing = connectionValidator.waitForDatabase(primaryTemplate, "Primary");
        List<String> required = List.of("user_profiles", "transactions", "merchant_directory");
        for (String col : required) {
            if (!existing.contains(col)) {
                primaryTemplate.createCollection(col);
                log.info("✅ Created missing primary collection: {}", col);
            }
        }

        // 1. Queryable Encryption Key Vault
        encryptionKeyInitializer.initializeKeyVault();

        // 2. Atlas Vector Search Index (behavioral fingerprints)
        vectorSearchIndexInitializer.createBehavioralVectorIndex();

        // 3. HITL Database
        hitlDatabaseInitializer.initialize();

        // 4. Memory Database (temporal + RAG + chat memory)
        memoryDatabaseInitializer.initialize();

        // 5. Seed demo user profiles (with encrypted PII)
        userProfileInitializer.seedDemoUsers();

        // 6. Seed RAG knowledge asynchronously (Voyage AI API — non-blocking)
        Thread.ofVirtual().name("rag-seed").start(ragKnowledgeSeeder::seed);
    }
}

package com.ayedata.init;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;

/**
 * Manages MongoDB Queryable Encryption key vault initialization.
 * Generates and stores Data Encryption Keys (DEKs) on startup (idempotent).
 */
@Component
public class EncryptionKeyInitializer {

    private static final Logger log = LoggerFactory.getLogger(EncryptionKeyInitializer.class);

    private final MongoClient primaryMongoClient;
    private final MongoTemplate primaryTemplate;

    public EncryptionKeyInitializer(
            @Qualifier("primaryMongoClient") MongoClient primaryMongoClient,
            @Qualifier("primaryMongoTemplate") MongoTemplate primaryTemplate) {
        this.primaryMongoClient = primaryMongoClient;
        this.primaryTemplate = primaryTemplate;
    }

    /**
     * Generate Queryable Encryption Master Key and Data Encryption Key (DEK).
     * Idempotent — checks if key already exists before creating.
     */
    public void initializeKeyVault() {
        try {
            log.info("🔐 Initializing MongoDB Queryable Encryption Key Vault...");

            MongoDatabase keyVaultDb = primaryMongoClient.getDatabase("keyvault");

            if (!keyVaultDb.listCollectionNames().into(new ArrayList<>()).contains("keys")) {
                keyVaultDb.createCollection("keys");
                log.info("✅ Created key vault collection");
            }

            MongoCollection<Document> keysCollection = keyVaultDb.getCollection("keys");

            Document existingKey = keysCollection.find(new Document("keyType", "local")).first();
            if (existingKey != null) {
                log.info("✅ Queryable Encryption key already exists (idempotent). KeyId: {}",
                        existingKey.getObjectId("_id"));
                return;
            }

            byte[] keyMaterial = generateSecureRandomBytes(96);

            Document dataEncryptionKey = new Document()
                    .append("keyType", "local")
                    .append("key", keyMaterial)
                    .append("createdAt", new Date())
                    .append("algorithm", "AEAD_AES_256_CBC_HMAC_SHA_512")
                    .append("status", "ACTIVE")
                    .append("keyRotationSchedule", "HOURLY");

            keysCollection.insertOne(dataEncryptionKey);

            log.info("✅ Queryable Encryption Key Generated - KeyId: {}",
                    dataEncryptionKey.getObjectId("_id"));

            Document auditEntry = new Document()
                    .append("event", "QE_KEY_GENERATED")
                    .append("keyId", dataEncryptionKey.getObjectId("_id").toString())
                    .append("algorithm", "AES-256-GCM")
                    .append("source", "SYSTEM_INITIALIZATION")
                    .append("timestamp", new Date())
                    .append("status", "SUCCESS");

            primaryTemplate.getCollection("system_audit_logs").insertOne(auditEntry);

            keysCollection.createIndex(new Document("status", 1));
            keysCollection.createIndex(new Document("createdAt", 1));
            keysCollection.createIndex(new Document("keyType", 1));

            log.info("✅ Key Vault collection indexes created");

        } catch (Exception e) {
            log.error("❌ Failed to initialize Queryable Encryption key", e);
            throw new RuntimeException("QE Initialization failed", e);
        }
    }

    private byte[] generateSecureRandomBytes(int length) {
        byte[] key = new byte[length];
        SecureRandom random = new SecureRandom();
        random.nextBytes(key);
        return key;
    }
}

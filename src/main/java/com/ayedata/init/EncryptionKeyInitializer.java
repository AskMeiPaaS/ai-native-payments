package com.ayedata.init;

import com.mongodb.ConnectionString;
import com.mongodb.ClientEncryptionSettings;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Filters;
import com.mongodb.client.vault.ClientEncryption;
import com.mongodb.client.vault.ClientEncryptions;
import com.mongodb.client.model.vault.DataKeyOptions;
import org.bson.Document;
import org.bson.BsonBinary;
import org.bson.UuidRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Manages MongoDB Queryable Encryption key vault initialization.
 * Generates and stores Data Encryption Keys (DEKs) on startup (idempotent).
 */
@Component
public class EncryptionKeyInitializer {

    private static final Logger log = LoggerFactory.getLogger(EncryptionKeyInitializer.class);

    private final MongoTemplate primaryTemplate;

    @Value("${spring.data.mongodb.primary.uri}")
    private String primaryUri;

    @Value("${app.mongodb.qe.key-vault-database:keyvault}")
    private String keyVaultDatabase;

    @Value("${app.mongodb.qe.key-vault-collection:keys}")
    private String keyVaultCollection;

    @Value("${app.mongodb.qe.user-profile-key-alt-name:user-profile-key}")
    private String userProfileKeyAltName;

    @Value("${app.mongodb.qe.local-master-key-base64}")
    private String localMasterKeyBase64;

    public EncryptionKeyInitializer(
            @Qualifier("primaryMongoTemplate") MongoTemplate primaryTemplate) {
        this.primaryTemplate = primaryTemplate;
    }

    /**
     * Generate Queryable Encryption Master Key and Data Encryption Key (DEK).
     * Idempotent — checks if key already exists before creating.
     */
    public void initializeKeyVault() {
        log.info("🔐 Initializing MongoDB Queryable Encryption Key Vault...");

        try (MongoClient keyVaultClient = MongoClients.create(
                MongoClientSettings.builder()
                        .applyConnectionString(new ConnectionString(primaryUri))
                        .uuidRepresentation(UuidRepresentation.STANDARD)
                        .build())) {

            MongoDatabase keyVaultDb = keyVaultClient.getDatabase(keyVaultDatabase);

            if (!keyVaultDb.listCollectionNames().into(new ArrayList<>()).contains(keyVaultCollection)) {
                keyVaultDb.createCollection(keyVaultCollection);
                log.info("✅ Created key vault collection {}", keyVaultCollection);
            }

            MongoCollection<Document> keysCollection = keyVaultDb.getCollection(keyVaultCollection);

            keysCollection.createIndex(
                    Indexes.ascending("keyAltNames"),
                    new IndexOptions()
                            .unique(true)
                            .partialFilterExpression(Filters.exists("keyAltNames")));

            Document existingKey = keysCollection.find(Filters.eq("keyAltNames", userProfileKeyAltName)).first();
            if (existingKey != null) {
                log.info("✅ Queryable Encryption DEK already exists for keyAltName='{}'", userProfileKeyAltName);
                return;
            }

            Map<String, Map<String, Object>> kmsProviders = Map.of(
                    "local", Map.of("key", decodeLocalMasterKey(localMasterKeyBase64)));

            String keyVaultNamespace = keyVaultDatabase + "." + keyVaultCollection;
            ClientEncryptionSettings clientEncryptionSettings = ClientEncryptionSettings.builder()
                    .keyVaultMongoClientSettings(MongoClientSettings.builder()
                            .applyConnectionString(new ConnectionString(primaryUri))
                            .uuidRepresentation(UuidRepresentation.STANDARD)
                            .build())
                    .keyVaultNamespace(keyVaultNamespace)
                    .kmsProviders(kmsProviders)
                    .build();

            BsonBinary keyId;
            try (ClientEncryption clientEncryption = ClientEncryptions.create(clientEncryptionSettings)) {
                keyId = clientEncryption.createDataKey("local", new DataKeyOptions()
                        .keyAltNames(List.of(userProfileKeyAltName)));
            }

            Document auditEntry = new Document()
                    .append("event", "QE_DATA_KEY_GENERATED")
                    .append("keyAltName", userProfileKeyAltName)
                    .append("keyId", keyId.asUuid().toString())
                    .append("source", "SYSTEM_INITIALIZATION")
                    .append("timestamp", new Date())
                    .append("status", "SUCCESS");

            primaryTemplate.getCollection("system_audit_logs").insertOne(auditEntry);
            log.info("✅ Queryable Encryption DEK generated for keyAltName='{}'", userProfileKeyAltName);

        } catch (Exception e) {
            log.error("❌ Failed to initialize Queryable Encryption key", e);
            throw new RuntimeException("QE Initialization failed", e);
        }
    }

    private byte[] decodeLocalMasterKey(String keyBase64) {
        byte[] decoded = Base64.getDecoder().decode(keyBase64);
        if (decoded.length != 96) {
            throw new IllegalStateException(
                    "app.mongodb.qe.local-master-key-base64 must decode to exactly 96 bytes");
        }
        return decoded;
    }
}

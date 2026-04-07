package com.ayedata.integration;

import com.mongodb.AutoEncryptionSettings;
import com.mongodb.ClientEncryptionSettings;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.vault.DataKeyOptions;
import com.mongodb.client.vault.ClientEncryption;
import com.mongodb.client.vault.ClientEncryptions;
import org.bson.BsonBinary;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.UuidRepresentation;
import org.bson.types.Binary;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Optional integration test for MongoDB Queryable Encryption.
 *
 * To run:
 * - set QE_IT_ENABLED=true
 * - set QE_IT_URI to a reachable MongoDB URI
 * - optionally set QE_IT_DB (default: pass_qe_it)
 * - optionally set QE_IT_LOCAL_MASTER_KEY_BASE64 (must decode to 96 bytes)
 */
@EnabledIfEnvironmentVariable(named = "QE_IT_ENABLED", matches = "true")
class QueryableEncryptionIntegrationTest {

    private static final String DEFAULT_MASTER_KEY_BASE64 =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+P0BBQkNERUZHSElKS0xNTk9QUVJTVFVWV1hZWltcXV5f";

    @Test
    void encryptsAtRestAndDecryptsOnRead() {
        String uri = envOrThrow("QE_IT_URI");
        String dbName = envOrDefault("QE_IT_DB", "pass_qe_it");
        String keyVaultNamespace = dbName + ".keyvault_keys";
        String collectionName = "user_profiles_qe_it";
        String keyAltName = "qe-it-key";

        byte[] localMasterKey = decodeMasterKey(envOrDefault("QE_IT_LOCAL_MASTER_KEY_BASE64", DEFAULT_MASTER_KEY_BASE64));
        Map<String, Map<String, Object>> kmsProviders = Map.of("local", Map.of("key", localMasterKey));

        ensureDataKeyExists(uri, keyVaultNamespace, kmsProviders, keyAltName);

        BsonDocument schema = BsonDocument.parse("""
                {
                  \"bsonType\": \"object\",
                  \"properties\": {
                    \"email\": {
                      \"encrypt\": {
                        \"bsonType\": \"string\",
                        \"algorithm\": \"AEAD_AES_256_CBC_HMAC_SHA_512-Deterministic\",
                        \"keyAltName\": \"qe-it-key\"
                      }
                    },
                    \"phone\": {
                      \"encrypt\": {
                        \"bsonType\": \"string\",
                        \"algorithm\": \"AEAD_AES_256_CBC_HMAC_SHA_512-Deterministic\",
                        \"keyAltName\": \"qe-it-key\"
                      }
                    }
                  }
                }
                """);

        Map<String, BsonDocument> schemaMap = Map.of(dbName + "." + collectionName, schema);

        MongoClientSettings encryptedSettings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .autoEncryptionSettings(AutoEncryptionSettings.builder()
                        .keyVaultNamespace(keyVaultNamespace)
                        .kmsProviders(kmsProviders)
                        .schemaMap(schemaMap)
                        .keyVaultMongoClientSettings(MongoClientSettings.builder()
                                .applyConnectionString(new ConnectionString(uri))
                                .uuidRepresentation(UuidRepresentation.STANDARD)
                                .build())
                        .build())
                .build();

        Document inserted;
        String userId = "qe-it-" + UUID.randomUUID();

        try (MongoClient encryptedClient = MongoClients.create(encryptedSettings)) {
            MongoCollection<Document> encryptedCollection =
                    encryptedClient.getDatabase(dbName).getCollection(collectionName);

            inserted = new Document("_id", userId)
                    .append("email", "alice@example.com")
                    .append("phone", "+91-99999-88888");

            encryptedCollection.insertOne(inserted);

            Document decryptedRead = encryptedCollection.find(Filters.eq("_id", userId)).first();
            Assertions.assertNotNull(decryptedRead);
            Assertions.assertEquals("alice@example.com", decryptedRead.getString("email"));
            Assertions.assertEquals("+91-99999-88888", decryptedRead.getString("phone"));
        }

        try (MongoClient plainClient = MongoClients.create(
                MongoClientSettings.builder()
                        .applyConnectionString(new ConnectionString(uri))
                        .uuidRepresentation(UuidRepresentation.STANDARD)
                        .build())) {

            MongoCollection<Document> rawCollection =
                    plainClient.getDatabase(dbName).getCollection(collectionName);

            Document raw = rawCollection.find(Filters.eq("_id", userId)).first();
            Assertions.assertNotNull(raw);
            Assertions.assertTrue(raw.get("email") instanceof Binary,
                    "Expected encrypted BSON binary for email at rest");
            Assertions.assertTrue(raw.get("phone") instanceof Binary,
                    "Expected encrypted BSON binary for phone at rest");

            rawCollection.deleteOne(Filters.eq("_id", userId));
        }
    }

    private static void ensureDataKeyExists(
            String uri,
            String keyVaultNamespace,
            Map<String, Map<String, Object>> kmsProviders,
            String keyAltName) {

        String[] parts = keyVaultNamespace.split("\\.", 2);
        String keyVaultDb = parts[0];
        String keyVaultCollection = parts[1];

        try (MongoClient plainClient = MongoClients.create(
                MongoClientSettings.builder()
                        .applyConnectionString(new ConnectionString(uri))
                        .uuidRepresentation(UuidRepresentation.STANDARD)
                        .build())) {

            MongoCollection<Document> keyVault = plainClient
                    .getDatabase(keyVaultDb)
                    .getCollection(keyVaultCollection);

            Document existing = keyVault.find(Filters.eq("keyAltNames", keyAltName)).first();
            if (existing != null) {
                return;
            }
        }

        ClientEncryptionSettings ceSettings = ClientEncryptionSettings.builder()
                .keyVaultMongoClientSettings(MongoClientSettings.builder()
                        .applyConnectionString(new ConnectionString(uri))
                        .uuidRepresentation(UuidRepresentation.STANDARD)
                        .build())
                .keyVaultNamespace(keyVaultNamespace)
                .kmsProviders(kmsProviders)
                .build();

        try (ClientEncryption clientEncryption = ClientEncryptions.create(ceSettings)) {
            BsonBinary keyId = clientEncryption.createDataKey("local", new DataKeyOptions()
                    .keyAltNames(List.of(keyAltName)));
            Assertions.assertNotNull(keyId);
        }
    }

    private static String envOrThrow(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + key);
        }
        return value;
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private static byte[] decodeMasterKey(String keyBase64) {
        byte[] key = Base64.getDecoder().decode(keyBase64);
        if (key.length != 96) {
            throw new IllegalStateException("QE_IT_LOCAL_MASTER_KEY_BASE64 must decode to exactly 96 bytes");
        }
        return key;
    }
}

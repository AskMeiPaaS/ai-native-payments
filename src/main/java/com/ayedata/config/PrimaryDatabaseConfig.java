package com.ayedata.config;

import com.mongodb.AutoEncryptionSettings;
import com.mongodb.ClientEncryptionSettings;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.vault.DataKeyOptions;
import com.mongodb.client.vault.ClientEncryption;
import com.mongodb.client.vault.ClientEncryptions;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.UuidRepresentation;
import org.bson.types.Binary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Configuration
public class PrimaryDatabaseConfig {
    private static final Logger log = LoggerFactory.getLogger(PrimaryDatabaseConfig.class);
    private static final String USER_PROFILE_NAMESPACE = "user_profiles";

    @Value("${spring.data.mongodb.primary.uri}")
    private String primaryUri;

    @Value("${spring.data.mongodb.primary.database:pass_main}")
    private String primaryDatabase;

    @Value("${app.mongodb.qe.enabled:true}")
    private boolean qeEnabled;

    @Value("${app.mongodb.qe.key-vault-database:keyvault}")
    private String keyVaultDatabase;

    @Value("${app.mongodb.qe.key-vault-collection:keys}")
    private String keyVaultCollection;

    @Value("${app.mongodb.qe.schema.user-profiles:encryption-schemas/user_profiles.json}")
    private String userProfilesSchemaResource;

    @Value("${app.mongodb.qe.local-master-key-base64}")
    private String localMasterKeyBase64;

    @Value("${app.mongodb.qe.crypt-shared-lib-path:}")
    private String cryptSharedLibPath;

    @Value("${app.mongodb.qe.user-profile-key-alt-name:user-profile-key}")
    private String userProfileKeyAltName;

    @Bean(name = "primaryMongoClient")
    public MongoClient primaryMongoClient() {
        log.info("Initializing Primary MongoDB Client...");
        MongoClientSettings baseSettings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(primaryUri))
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .build();

        MongoClientSettings settingsToUse = baseSettings;
        boolean qeRequested = qeEnabled;

        if (qeEnabled) {
            try {
                settingsToUse = MongoClientSettings.builder(baseSettings)
                        .autoEncryptionSettings(buildAutoEncryptionSettings())
                        .build();
                log.info("✅ MongoDB Queryable Encryption enabled for {}.{}", primaryDatabase, USER_PROFILE_NAMESPACE);
            } catch (RuntimeException ex) {
                log.error("❌ Failed to enable MongoDB Queryable Encryption. Falling back to non-QE MongoClient.", ex);
                settingsToUse = baseSettings;
            }
        } else {
            log.warn("⚠️ MongoDB Queryable Encryption is disabled via app.mongodb.qe.enabled=false");
        }

        try {
            return MongoClients.create(settingsToUse);
        } catch (RuntimeException ex) {
            if (qeRequested && settingsToUse != baseSettings) {
                log.error("❌ MongoDB client creation with QE failed. Retrying with QE disabled.", ex);
                return MongoClients.create(baseSettings);
            }
            throw ex;
        }
    }

    /**
     * Shared factory — MUST be the same instance for both primaryMongoTemplate
     * and MongoTransactionManager so Spring's @Transactional session-binding works.
     */
    @Primary
    @Bean(name = "primaryMongoDatabaseFactory")
    public MongoDatabaseFactory primaryMongoDatabaseFactory(
            @Qualifier("primaryMongoClient") MongoClient primaryMongoClient) {
        return new SimpleMongoClientDatabaseFactory(primaryMongoClient, primaryDatabase);
    }

    @Primary
    @Bean(name = "primaryMongoTemplate")
    public MongoTemplate primaryMongoTemplate(
            @Qualifier("primaryMongoDatabaseFactory") MongoDatabaseFactory dbFactory) {
        log.info("Creating Primary MongoTemplate");
        return new MongoTemplate(dbFactory);
    }

    @Bean
    public MongoTransactionManager transactionManager(
            @Qualifier("primaryMongoDatabaseFactory") MongoDatabaseFactory dbFactory) {
        log.info("✅ MongoTransactionManager configured — @Transactional is now active for MongoDB");
        return new MongoTransactionManager(dbFactory);
    }

    private AutoEncryptionSettings buildAutoEncryptionSettings() {
        String keyVaultNamespace = keyVaultDatabase + "." + keyVaultCollection;

        byte[] localMasterKey = decodeLocalMasterKey(localMasterKeyBase64);
        Map<String, Map<String, Object>> kmsProviders = Map.of(
                "local", Map.of("key", (Object) localMasterKey));

        // Resolve the actual DEK keyId from the key vault (create if absent)
        BsonBinary dekKeyId = resolveOrCreateDek(keyVaultNamespace, kmsProviders, userProfileKeyAltName);

        // Load schema template and replace keyAltName with the real keyId
        BsonDocument rawSchema = loadSchemaDocument(userProfilesSchemaResource);
        BsonDocument resolvedSchema = replaceKeyAltNamesWithKeyId(rawSchema, dekKeyId);

        Map<String, BsonDocument> schemaMap = Map.of(
                primaryDatabase + "." + USER_PROFILE_NAMESPACE, resolvedSchema);

        AutoEncryptionSettings.Builder builder = AutoEncryptionSettings.builder()
                .keyVaultNamespace(keyVaultNamespace)
                .kmsProviders(kmsProviders)
                .schemaMap(schemaMap)
                .keyVaultMongoClientSettings(MongoClientSettings.builder()
                        .applyConnectionString(new ConnectionString(primaryUri))
                        .uuidRepresentation(UuidRepresentation.STANDARD)
                        .build());

        Map<String, Object> extraOptions = new HashMap<>();
        // Never spawn mongocryptd — we rely on crypt_shared exclusively
        extraOptions.put("mongocryptdBypassSpawn", true);

        if (!cryptSharedLibPath.isBlank()) {
            if (isCompatibleCryptSharedLibrary(cryptSharedLibPath)) {
                extraOptions.put("cryptSharedLibPath", cryptSharedLibPath);
                extraOptions.put("cryptSharedLibRequired", true);
                log.info("Using crypt_shared library for QE: {}", cryptSharedLibPath);
            } else {
                log.warn("Ignoring crypt-shared-lib-path (missing or incompatible for this OS): {}", cryptSharedLibPath);
            }
        }

        builder.extraOptions(extraOptions);
        return builder.build();
    }

    /**
     * Look up the DEK in the key vault by keyAltName.  If it doesn't exist yet,
     * create one via ClientEncryption so the schema can reference it by keyId.
     */
    private BsonBinary resolveOrCreateDek(
            String keyVaultNamespace,
            Map<String, Map<String, Object>> kmsProviders,
            String keyAltName) {

        MongoClientSettings plainSettings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(primaryUri))
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .build();

        // Try to find existing key first
        try (MongoClient plainClient = MongoClients.create(plainSettings)) {
            MongoCollection<Document> keyVault = plainClient
                    .getDatabase(keyVaultDatabase)
                    .getCollection(keyVaultCollection);

            // Ensure unique index (idempotent)
            keyVault.createIndex(
                    Indexes.ascending("keyAltNames"),
                    new IndexOptions()
                            .unique(true)
                            .partialFilterExpression(Filters.exists("keyAltNames")));

            Document existing = keyVault.find(Filters.eq("keyAltNames", keyAltName)).first();
            if (existing != null) {
                Object rawId = existing.get("_id");
                if (rawId instanceof Binary binary) {
                    log.info("Found existing DEK for keyAltName='{}'", keyAltName);
                    return new BsonBinary(binary.getType(), binary.getData());
                }
                if (rawId instanceof UUID uuid) {
                    log.info("Found existing DEK for keyAltName='{}'", keyAltName);
                    return new BsonBinary(uuid, UuidRepresentation.STANDARD);
                }
            }
        }

        // Key doesn't exist — create one
        ClientEncryptionSettings ceSettings = ClientEncryptionSettings.builder()
                .keyVaultMongoClientSettings(plainSettings)
                .keyVaultNamespace(keyVaultNamespace)
                .kmsProviders(kmsProviders)
                .build();

        try (ClientEncryption clientEncryption = ClientEncryptions.create(ceSettings)) {
            BsonBinary keyId = clientEncryption.createDataKey("local",
                    new DataKeyOptions().keyAltNames(List.of(keyAltName)));
            log.info("✅ Created new DEK for keyAltName='{}', keyId={}", keyAltName, keyId.asUuid());
            return keyId;
        }
    }

    /**
     * Walk the schema document and replace every {@code "keyAltName": "…"} inside
     * {@code "encrypt"} blocks with {@code "keyId": [ <binaryKeyId> ]}.
     */
    private BsonDocument replaceKeyAltNamesWithKeyId(BsonDocument schema, BsonBinary keyId) {
        BsonDocument result = new BsonDocument();
        for (String key : schema.keySet()) {
            if (key.equals("encrypt") && schema.get(key).isDocument()) {
                BsonDocument encryptBlock = schema.getDocument(key).clone();
                if (encryptBlock.containsKey("keyAltName")) {
                    encryptBlock.remove("keyAltName");
                    encryptBlock.put("keyId", new BsonArray(List.of(keyId)));
                }
                result.put(key, encryptBlock);
            } else if (schema.get(key).isDocument()) {
                result.put(key, replaceKeyAltNamesWithKeyId(schema.getDocument(key), keyId));
            } else {
                result.put(key, schema.get(key));
            }
        }
        return result;
    }

    private boolean isCompatibleCryptSharedLibrary(String libraryPath) {
        try {
            Path path = Path.of(libraryPath);
            if (!Files.isRegularFile(path)) {
                return false;
            }

            byte[] header = Files.readAllBytes(path);
            if (header.length < 4) {
                return false;
            }

            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("linux")) {
                return (header[0] == 0x7f && header[1] == 0x45 && header[2] == 0x4c && header[3] == 0x46);
            }

            if (os.contains("mac") || os.contains("darwin")) {
                int magic = ((header[0] & 0xff) << 24)
                        | ((header[1] & 0xff) << 16)
                        | ((header[2] & 0xff) << 8)
                        | (header[3] & 0xff);
                return magic == 0xfeedface || magic == 0xfeedfacf
                        || magic == 0xcefaedfe || magic == 0xcffaedfe;
            }
        } catch (Exception ex) {
            log.warn("Unable to validate QE crypt shared library: {}", libraryPath, ex);
            return false;
        }
        return false;
    }

    private byte[] decodeLocalMasterKey(String keyBase64) {
        byte[] decoded = Base64.getDecoder().decode(keyBase64);
        if (decoded.length != 96) {
            throw new IllegalStateException(
                    "app.mongodb.qe.local-master-key-base64 must decode to exactly 96 bytes");
        }
        return decoded;
    }

    private BsonDocument loadSchemaDocument(String schemaPath) {
        try {
            ClassPathResource resource = new ClassPathResource(schemaPath);
            String json = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return BsonDocument.parse(json);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load QE schema resource: " + schemaPath, e);
        }
    }
}

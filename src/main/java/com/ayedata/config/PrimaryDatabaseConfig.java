package com.ayedata.config;

import com.mongodb.AutoEncryptionSettings;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.BsonDocument;
import org.bson.UuidRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

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

    @Bean(name = "primaryMongoClient")
    public MongoClient primaryMongoClient() {
        log.info("Initializing Primary MongoDB Client...");
        MongoClientSettings.Builder settingsBuilder = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(primaryUri))
                .uuidRepresentation(UuidRepresentation.STANDARD);

        if (qeEnabled) {
            settingsBuilder.autoEncryptionSettings(buildAutoEncryptionSettings());
            log.info("✅ MongoDB Queryable Encryption enabled for {}.{}", primaryDatabase, USER_PROFILE_NAMESPACE);
        } else {
            log.warn("⚠️ MongoDB Queryable Encryption is disabled via app.mongodb.qe.enabled=false");
        }

        return MongoClients.create(settingsBuilder.build());
    }

    @Primary
    @Bean(name = "primaryMongoTemplate")
    public MongoTemplate primaryMongoTemplate(
            @Qualifier("primaryMongoClient") MongoClient primaryMongoClient) {
        log.info("Creating Primary MongoTemplate");
        return new MongoTemplate(new SimpleMongoClientDatabaseFactory(primaryMongoClient, primaryDatabase));
    }

    private AutoEncryptionSettings buildAutoEncryptionSettings() {
        String keyVaultNamespace = keyVaultDatabase + "." + keyVaultCollection;

        Map<String, Map<String, Object>> kmsProviders = Map.of(
                "local", Map.of("key", decodeLocalMasterKey(localMasterKeyBase64)));

        Map<String, BsonDocument> schemaMap = Map.of(
                primaryDatabase + "." + USER_PROFILE_NAMESPACE,
                loadSchemaDocument(userProfilesSchemaResource));

        AutoEncryptionSettings.Builder builder = AutoEncryptionSettings.builder()
                .keyVaultNamespace(keyVaultNamespace)
                .kmsProviders(kmsProviders)
                .schemaMap(schemaMap)
                .keyVaultMongoClientSettings(MongoClientSettings.builder()
                        .applyConnectionString(new ConnectionString(primaryUri))
                        .uuidRepresentation(UuidRepresentation.STANDARD)
                        .build());

        if (!cryptSharedLibPath.isBlank()) {
            Map<String, Object> extraOptions = new HashMap<>();
            extraOptions.put("cryptSharedLibPath", cryptSharedLibPath);
            builder.extraOptions(extraOptions);
            log.info("Using explicit crypt shared library path for QE.");
        }

        return builder.build();
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

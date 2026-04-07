package com.ayedata.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.Binary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Application-level AES-256-GCM encryption service for PII fields stored in MongoDB.
 *
 * <p>This service simulates MongoDB Queryable Encryption by deriving a 256-bit AES key
 * from the Data Encryption Key (DEK) stored in the key vault ({@code keyvault.keys}),
 * and applying authenticated AES-GCM encryption to sensitive fields (email, phone)
 * before they are persisted in the {@code user_profiles} collection.
 *
 * <p>Encrypted value format: {@code Base64(IV[12] | ciphertext | GCM-tag[16])}
 */
@Service
public class UserProfileEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileEncryptionService.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String ENCRYPTED_MARKER = "ENC:";

    private final MongoClient primaryMongoClient;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Lazily resolved and cached encryption key (32 bytes of the vault DEK). */
    private volatile SecretKey cachedKey;

    public UserProfileEncryptionService(
            @Qualifier("primaryMongoClient") MongoClient primaryMongoClient) {
        this.primaryMongoClient = primaryMongoClient;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Encrypts a plain-text PII value.
     *
     * @param plaintext the raw PII value (e.g. email or phone number)
     * @return Base64-encoded ciphertext prefixed with {@code "ENC:"}, or the
     *         original value if encryption fails (graceful degradation for demos)
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        try {
            SecretKey key = getOrLoadKey();
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Pack: IV || ciphertext+tag
            byte[] packed = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array();

            return ENCRYPTED_MARKER + Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            log.warn("⚠️ PII encryption failed; storing plaintext (demo fallback): {}", e.getMessage());
            return plaintext;
        }
    }

    /**
     * Decrypts a previously encrypted PII value.
     *
     * @param ciphertext the {@code "ENC:"}-prefixed Base64 string returned by {@link #encrypt}
     * @return the original plain-text value, or the input unchanged if it is not an
     *         encrypted string
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || !ciphertext.startsWith(ENCRYPTED_MARKER)) {
            return ciphertext;
        }
        try {
            SecretKey key = getOrLoadKey();
            byte[] packed = Base64.getDecoder().decode(ciphertext.substring(ENCRYPTED_MARKER.length()));

            ByteBuffer buf = ByteBuffer.wrap(packed);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buf.get(iv);
            byte[] encData = new byte[buf.remaining()];
            buf.get(encData);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plainBytes = cipher.doFinal(encData);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("⚠️ PII decryption failed; returning raw stored value: {}", e.getMessage());
            return ciphertext;
        }
    }

    // -------------------------------------------------------------------------
    // Key management
    // -------------------------------------------------------------------------

    private SecretKey getOrLoadKey() {
        if (cachedKey != null) {
            return cachedKey;
        }
        synchronized (this) {
            if (cachedKey != null) {
                return cachedKey;
            }
            cachedKey = loadKeyFromVault();
            return cachedKey;
        }
    }

    /**
     * Reads the first 32 bytes of the DEK from {@code keyvault.keys} and builds
     * an AES-256 {@link SecretKey}.
     */
    private SecretKey loadKeyFromVault() {
        try {
            MongoCollection<Document> keysCollection =
                    primaryMongoClient.getDatabase("keyvault").getCollection("keys");

            Document keyDoc = keysCollection.find(new Document("keyType", "local")).first();
            if (keyDoc == null) {
                throw new IllegalStateException("No encryption key found in keyvault.keys");
            }

            Object rawKey = keyDoc.get("key");
            byte[] keyMaterial;
            if (rawKey instanceof Binary binary) {
                keyMaterial = binary.getData();
            } else if (rawKey instanceof byte[] bytes) {
                keyMaterial = bytes;
            } else {
                throw new IllegalStateException("Unexpected key material type: " + rawKey.getClass());
            }

            // Use first 32 bytes for AES-256
            byte[] aesKey = Arrays.copyOf(keyMaterial, 32);
            SecretKey secretKey = new SecretKeySpec(aesKey, "AES");
            log.info("✅ Loaded AES-256 PII encryption key from keyvault (key material: {}b)", keyMaterial.length);
            return secretKey;
        } catch (Exception e) {
            log.error("❌ Failed to load PII encryption key from vault: {}", e.getMessage());
            throw new RuntimeException("PII encryption key load failed", e);
        }
    }
}

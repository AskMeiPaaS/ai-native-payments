package com.ayedata.domain;

import java.time.Instant;
import java.util.List;

/**
 * Tracks encrypted fields and key rotation metadata for a transaction.
 */
public class EncryptionMetadata {
    private List<String> keyIdsUsed;
    private Instant encryptedAt;
    private String encryptionAlgorithm;

    public EncryptionMetadata() {}

    public EncryptionMetadata(List<String> keyIdsUsed, Instant encryptedAt, String encryptionAlgorithm) {
        this.keyIdsUsed = keyIdsUsed;
        this.encryptedAt = encryptedAt;
        this.encryptionAlgorithm = encryptionAlgorithm;
    }

    public List<String> getKeyIdsUsed() { return keyIdsUsed; }
    public void setKeyIdsUsed(List<String> keyIdsUsed) { this.keyIdsUsed = keyIdsUsed; }

    public Instant getEncryptedAt() { return encryptedAt; }
    public void setEncryptedAt(Instant encryptedAt) { this.encryptedAt = encryptedAt; }

    public String getEncryptionAlgorithm() { return encryptionAlgorithm; }
    public void setEncryptionAlgorithm(String encryptionAlgorithm) { this.encryptionAlgorithm = encryptionAlgorithm; }
}

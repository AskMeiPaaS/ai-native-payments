package com.ayedata.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * User Profile with Queryable Encryption for PII fields
 * 
 * Encrypted Fields (Deterministic - Queryable):
 * - email
 * - phone
 * - bank_account
 * 
 * Encrypted Fields (Random - Non-queryable, higher security):
 * - ssn
 */
@Document(collection = "user_profiles")
public class UserProfile {
    @Id
    private String id;
    
    // Encrypted PII fields (client-side encryption via MongoDB QE)
    private String email;              // AEAD_AES_256_CBC_HMAC_SHA_512-Deterministic (queryable)
    private String phone;              // AEAD_AES_256_CBC_HMAC_SHA_512-Deterministic (queryable)
    private String ssn;                // AEAD_AES_256_CBC_HMAC_SHA_512-Random (non-queryable)
    private String bank_account;       // AEAD_AES_256_CBC_HMAC_SHA_512-Deterministic (queryable)
    
    // Non-encrypted behavioral data
    private BehavioralFingerprint behavioralFingerprint;

    // Dashboard-friendly account state
    private String displayName;
    private double currentBalance;
    private String currency;
    private Instant lastUpdatedAt;

    // Constructor
    public UserProfile() {}

    public UserProfile(String id, BehavioralFingerprint behavioralFingerprint) {
        this.id = id;
        this.behavioralFingerprint = behavioralFingerprint;
        this.currency = "INR";
        this.lastUpdatedAt = Instant.now();
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getSsn() {
        return ssn;
    }

    public String getBank_account() {
        return bank_account;
    }

    public BehavioralFingerprint getBehavioralFingerprint() {
        return behavioralFingerprint;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getCurrentBalance() {
        return currentBalance;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    // Setters
    public void setId(String id) {
        this.id = id;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public void setSsn(String ssn) {
        this.ssn = ssn;
    }

    public void setBank_account(String bank_account) {
        this.bank_account = bank_account;
    }

    public void setBehavioralFingerprint(BehavioralFingerprint behavioralFingerprint) {
        this.behavioralFingerprint = behavioralFingerprint;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setCurrentBalance(double currentBalance) {
        this.currentBalance = currentBalance;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public void setLastUpdatedAt(Instant lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserProfile that = (UserProfile) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    // Inner class: BehavioralFingerprint
    public static class BehavioralFingerprint {
        private List<Double> baselineVector;
        private List<String> trustedDevices;

        // Constructor
        public BehavioralFingerprint() {}

        public BehavioralFingerprint(List<Double> baselineVector, List<String> trustedDevices) {
            this.baselineVector = baselineVector;
            this.trustedDevices = trustedDevices;
        }

        // Getters
        public List<Double> getBaselineVector() {
            return baselineVector;
        }

        public List<String> getTrustedDevices() {
            return trustedDevices;
        }

        // Setters
        public void setBaselineVector(List<Double> baselineVector) {
            this.baselineVector = baselineVector;
        }

        public void setTrustedDevices(List<String> trustedDevices) {
            this.trustedDevices = trustedDevices;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BehavioralFingerprint that = (BehavioralFingerprint) o;
            return Objects.equals(baselineVector, that.baselineVector) &&
                    Objects.equals(trustedDevices, that.trustedDevices);
        }

        @Override
        public int hashCode() {
            return Objects.hash(baselineVector, trustedDevices);
        }
    }
}
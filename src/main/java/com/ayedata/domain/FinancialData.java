package com.ayedata.domain;

/**
 * Financial data with Queryable Encryption for PII fields.
 *
 * Encrypted Fields:
 * - user_email: AEAD_AES_256_CBC_HMAC_SHA_512-Deterministic (queryable)
 * - donor_account: AEAD_AES_256_CBC_HMAC_SHA_512-Deterministic (queryable)
 * - recipient_account: AEAD_AES_256_CBC_HMAC_SHA_512-Deterministic (queryable)
 */
public class FinancialData {
    private double amount;
    private String recipientBank;
    private String merchantId;
    private String user_email;
    private String donor_account;
    private String recipient_account;

    public FinancialData() {}

    private FinancialData(Builder builder) {
        this.amount = builder.amount;
        this.recipientBank = builder.recipientBank;
        this.merchantId = builder.merchantId;
        this.user_email = builder.user_email;
        this.donor_account = builder.donor_account;
        this.recipient_account = builder.recipient_account;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private double amount;
        private String recipientBank;
        private String merchantId;
        private String user_email;
        private String donor_account;
        private String recipient_account;

        public Builder amount(double amount) {
            this.amount = amount;
            return this;
        }

        public Builder recipientBank(String recipientBank) {
            this.recipientBank = recipientBank;
            return this;
        }

        public Builder merchantId(String merchantId) {
            this.merchantId = merchantId;
            return this;
        }

        public Builder user_email(String user_email) {
            this.user_email = user_email;
            return this;
        }

        public Builder donor_account(String donor_account) {
            this.donor_account = donor_account;
            return this;
        }

        public Builder recipient_account(String recipient_account) {
            this.recipient_account = recipient_account;
            return this;
        }

        public FinancialData build() {
            return new FinancialData(this);
        }
    }

    public double getAmount() { return amount; }
    public String getRecipientBank() { return recipientBank; }
    public String getMerchantId() { return merchantId; }
    public String getUser_email() { return user_email; }
    public String getDonor_account() { return donor_account; }
    public String getRecipient_account() { return recipient_account; }

    public void setAmount(double amount) { this.amount = amount; }
    public void setRecipientBank(String recipientBank) { this.recipientBank = recipientBank; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public void setUser_email(String user_email) { this.user_email = user_email; }
    public void setDonor_account(String donor_account) { this.donor_account = donor_account; }
    public void setRecipient_account(String recipient_account) { this.recipient_account = recipient_account; }
}

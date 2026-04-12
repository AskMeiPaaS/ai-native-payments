package com.ayedata.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "merchant_directory")
public class MerchantProfile {
    @Id
    private String merchantId;
    private String name;
    private String category;
    private String mccCode;
    private String upiVpa;
    private String accountNumber;
    private double maxTransactionLimit;
    private List<String> supportedChannels;
    private String city;
    private String state;
    private boolean verified;
    private Instant createdAt;

    public MerchantProfile() {}

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getMccCode() { return mccCode; }
    public void setMccCode(String mccCode) { this.mccCode = mccCode; }
    public String getUpiVpa() { return upiVpa; }
    public void setUpiVpa(String upiVpa) { this.upiVpa = upiVpa; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    public double getMaxTransactionLimit() { return maxTransactionLimit; }
    public void setMaxTransactionLimit(double maxTransactionLimit) { this.maxTransactionLimit = maxTransactionLimit; }
    public List<String> getSupportedChannels() { return supportedChannels; }
    public void setSupportedChannels(List<String> supportedChannels) { this.supportedChannels = supportedChannels; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

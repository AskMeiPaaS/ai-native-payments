package com.ayedata.service;

import com.ayedata.domain.FinancialData;
import com.ayedata.domain.TransactionRecord;
import com.ayedata.domain.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AccountBalanceService {
    public static final String DEFAULT_USER_ID = "demo-user";
    private static final Logger log = LoggerFactory.getLogger(AccountBalanceService.class);
    private static final double DEFAULT_OPENING_BALANCE = 25_000.00;
    private static final String DEFAULT_CURRENCY = "INR";

    private final MongoTemplate mongoTemplate;

    public AccountBalanceService(@Qualifier("primaryMongoTemplate") MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public String normalizeUserId(String userId) {
        return (userId == null || userId.isBlank()) ? DEFAULT_USER_ID : userId.trim();
    }

    public synchronized UserProfile getOrCreateProfile(String userId) {
        String resolvedUserId = normalizeUserId(userId);
        UserProfile existing = mongoTemplate.findById(resolvedUserId, UserProfile.class);

        if (existing != null) {
            boolean changed = false;

            if (existing.getDisplayName() == null || existing.getDisplayName().isBlank()) {
                existing.setDisplayName("Demo Customer");
                changed = true;
            }
            if (existing.getCurrency() == null || existing.getCurrency().isBlank()) {
                existing.setCurrency(DEFAULT_CURRENCY);
                changed = true;
            }
            if (existing.getBehavioralFingerprint() == null) {
                existing.setBehavioralFingerprint(new UserProfile.BehavioralFingerprint(List.of(), List.of("demo-mobile")));
                changed = true;
            }
            if (existing.getLastUpdatedAt() == null && existing.getCurrentBalance() <= 0.0) {
                existing.setCurrentBalance(DEFAULT_OPENING_BALANCE);
                changed = true;
            }

            if (changed) {
                existing.setLastUpdatedAt(Instant.now());
                mongoTemplate.save(existing);
            }
            return existing;
        }

        UserProfile created = new UserProfile();
        created.setId(resolvedUserId);
        created.setDisplayName("Demo Customer");
        created.setCurrency(DEFAULT_CURRENCY);
        created.setCurrentBalance(DEFAULT_OPENING_BALANCE);
        created.setLastUpdatedAt(Instant.now());
        created.setBehavioralFingerprint(new UserProfile.BehavioralFingerprint(List.of(0.98, 0.97, 0.96), List.of("trusted-mobile")));
        mongoTemplate.save(created);

        log.info("Created default dashboard account for user {} with opening balance ₹{}", resolvedUserId, DEFAULT_OPENING_BALANCE);
        return created;
    }

    public synchronized double creditBalance(String userId, double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (amount > 10_00_000.00) {
            throw new IllegalArgumentException("single top-up cannot exceed ₹10,00,000");
        }

        UserProfile profile = getOrCreateProfile(userId);
        double updatedBalance = roundCurrency(profile.getCurrentBalance() + amount);
        profile.setCurrentBalance(updatedBalance);
        profile.setLastUpdatedAt(Instant.now());
        mongoTemplate.save(profile);

        log.info("Credited ₹{} to user {}. New balance: ₹{}", amount, userId, updatedBalance);
        return updatedBalance;
    }

    public synchronized double debitBalance(String userId, double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }

        UserProfile profile = getOrCreateProfile(userId);
        double currentBalance = roundCurrency(profile.getCurrentBalance());

        if (amount > currentBalance) {
            throw new IllegalArgumentException(String.format("insufficient balance: available ₹%.2f", currentBalance));
        }

        double updatedBalance = roundCurrency(currentBalance - amount);
        if (updatedBalance < 0) {
            throw new IllegalArgumentException("amount would overdraw the account");
        }

        profile.setCurrentBalance(updatedBalance);
        profile.setLastUpdatedAt(Instant.now());
        mongoTemplate.save(profile);

        return updatedBalance;
    }

    public double getCurrentBalance(String userId) {
        return roundCurrency(getOrCreateProfile(userId).getCurrentBalance());
    }

    public Map<String, Object> getDashboard(String userId) {
        UserProfile profile = getOrCreateProfile(userId);

        Query query = new Query(Criteria.where("userId").is(profile.getId()))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(5);

        List<TransactionRecord> recentTransfers = mongoTemplate.find(query, TransactionRecord.class);

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("userId", profile.getId());
        dashboard.put("displayName", profile.getDisplayName());
        dashboard.put("currentBalance", roundCurrency(profile.getCurrentBalance()));
        dashboard.put("availableBalance", roundCurrency(profile.getCurrentBalance()));
        dashboard.put("currency", profile.getCurrency() == null ? DEFAULT_CURRENCY : profile.getCurrency());
        dashboard.put("lastUpdatedAt", profile.getLastUpdatedAt());

        if (recentTransfers.isEmpty()) {
            dashboard.put("lastTransferAmount", 0.0);
            dashboard.put("lastTransferStatus", "NO_TRANSFERS_YET");
            dashboard.put("lastTransactionType", "NONE");
        } else {
            TransactionRecord latestTransfer = recentTransfers.get(0);
            FinancialData financialData = latestTransfer.getFinancialData();
            dashboard.put("lastTransferAmount", financialData != null ? roundCurrency(financialData.getAmount()) : 0.0);
            dashboard.put("lastTransferStatus", latestTransfer.getStatus());
            String instrType = latestTransfer.getInstructionType();
            dashboard.put("lastTransactionType", (instrType != null && instrType.contains("RECEIVE")) ? "CREDIT" : "DEBIT");
        }

        List<Map<String, Object>> recentTransferSummaries = recentTransfers.stream()
                .map(this::toTransferSummary)
                .toList();
        dashboard.put("recentTransfers", recentTransferSummaries);

        return dashboard;
    }

    private Map<String, Object> toTransferSummary(TransactionRecord transactionRecord) {
        Map<String, Object> transfer = new LinkedHashMap<>();
        transfer.put("id", transactionRecord.getId());
        transfer.put("status", transactionRecord.getStatus());
        transfer.put("createdAt", transactionRecord.getCreatedAt());
        transfer.put("resultingBalance", transactionRecord.getResultingBalance());

        String instrType = transactionRecord.getInstructionType();
        String transactionType = (instrType != null && instrType.contains("RECEIVE")) ? "CREDIT" : "DEBIT";
        transfer.put("transactionType", transactionType);

        FinancialData financialData = transactionRecord.getFinancialData();
        if (financialData != null) {
            transfer.put("amount", roundCurrency(financialData.getAmount()));
            transfer.put("merchantId", financialData.getMerchantId());
            transfer.put("targetBank", financialData.getRecipientBank());
            transfer.put("sourceAccount", financialData.getDonor_account() != null
                    ? financialData.getDonor_account() : transactionRecord.getUserId());
            transfer.put("targetAccount", financialData.getRecipient_account() != null
                    ? financialData.getRecipient_account() : financialData.getMerchantId());
        } else {
            transfer.put("amount", 0.0);
            transfer.put("merchantId", "-");
            transfer.put("targetBank", "-");
            transfer.put("sourceAccount", "-");
            transfer.put("targetAccount", "-");
        }

        return transfer;
    }

    private double roundCurrency(double amount) {
        return Math.round(amount * 100.0) / 100.0;
    }
}

package com.ayedata.service;

import com.ayedata.domain.AgentReasoning;
import com.ayedata.domain.FinancialData;
import com.ayedata.domain.TransactionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class MongoLedgerService {
    private static final Logger log = LoggerFactory.getLogger(MongoLedgerService.class);

    private final MongoTemplate mongoTemplate;
    private final AccountBalanceService accountBalanceService;

    public MongoLedgerService(@Qualifier("primaryMongoTemplate") MongoTemplate mongoTemplate,
                                                          AccountBalanceService accountBalanceService) {
        this.mongoTemplate = mongoTemplate;
        this.accountBalanceService = accountBalanceService;
    }

        /**
         * Executes the financial mandate switch / transfer atomically.
         * The LLM selects the payment channel from RAG-enriched context before invoking this method.
         * If recipientUserId is non-null, the recipient's account is credited (P2P transfer).
         */
    @Transactional
    public String commitSwitchAtomic(String sessionId, String userId, String beneficiary, String targetBank,
            double amount, String recipientUserId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (beneficiary == null || beneficiary.isBlank()) {
            throw new IllegalArgumentException("beneficiary (name, account number, or merchant ID) is required");
        }
        if (targetBank == null || targetBank.isBlank()) {
            throw new IllegalArgumentException("targetBank is required");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }

        String resolvedUserId = accountBalanceService.normalizeUserId(userId);
        String maskedUserId = "****" + (resolvedUserId.length() > 4
                ? resolvedUserId.substring(resolvedUserId.length() - 4)
                : resolvedUserId);
        String selectedPaymentMethod = targetBank.trim();
        log.info("Executing ACID transaction for user {} moving ₹{} to {} via LLM-selected channel {}",
                maskedUserId, amount, beneficiary, targetBank);

        // Fraud analysis already performed by LedgerTools before calling this method.
        // We skip duplicate analysis here — the caller (LedgerTools) has already validated
        // BLOCK/ESCALATE actions and only calls commitSwitchAtomic for APPROVE/MONITOR.
        boolean requiresHitl = false;

        double resultingBalance = accountBalanceService.debitBalance(resolvedUserId, amount);

        // P2P: credit the recipient if they are a registered user
        if (recipientUserId != null && !recipientUserId.isBlank()
                && !recipientUserId.equalsIgnoreCase(resolvedUserId)) {
            double recipientNewBalance = accountBalanceService.creditBalance(recipientUserId, amount);
            log.info("P2P credit: ₹{} credited to recipient {}", amount, recipientUserId);

            // Save a CREDIT transaction record for the recipient so their ledger/dashboard shows the deposit
            String recipientTxnId = "TXN-PASS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            TransactionRecord recipientRecord = TransactionRecord.builder()
                    .id(recipientTxnId)
                    .sessionId(sessionId)
                    .userId(recipientUserId)
                    .instructionType("PASS_MONEY_RECEIVE")
                    .status("SETTLED")
                    .createdAt(Instant.now())
                    .resultingBalance(recipientNewBalance)
                    .paymentMethod(selectedPaymentMethod)
                    .requiresHitlReview(false)
                    .financialData(FinancialData.builder()
                            .amount(amount)
                            .merchantId(resolvedUserId)
                            .recipientBank(selectedPaymentMethod)
                            .donor_account(resolvedUserId)
                            .recipient_account(recipientUserId)
                            .build())
                    .agentReasoningSnapshot(AgentReasoning.builder()
                            .supervisorDecision("APPROVED")
                            .contextSimilarityScore(0.95)
                            .build())
                    .build();
            mongoTemplate.save(recipientRecord);
            log.info("Recipient ledger entry saved: {} for user {}", recipientTxnId, recipientUserId);
        }

        String txnId = "TXN-PASS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        TransactionRecord transaction = TransactionRecord.builder()
                .id(txnId)
                .sessionId(sessionId)
                .userId(resolvedUserId)
                .instructionType("PASS_MONEY_TRANSFER")
                .status("SETTLED")
                .createdAt(Instant.now())
                .resultingBalance(resultingBalance)
                .paymentMethod(selectedPaymentMethod)
                .requiresHitlReview(requiresHitl)
                .financialData(FinancialData.builder()
                        .amount(amount)
                        .merchantId(beneficiary)
                        .recipientBank(selectedPaymentMethod)
                        .donor_account(resolvedUserId)
                        .recipient_account(beneficiary)
                        .build())
                .agentReasoningSnapshot(AgentReasoning.builder()
                        .supervisorDecision("APPROVED")
                        .contextSimilarityScore(0.95)
                        .build())
                .build();

        mongoTemplate.save(transaction);

        log.info("Ledger commit successful: {} via {} (remaining balance ₹{})",
                txnId, selectedPaymentMethod, resultingBalance);
        return txnId;
    }

    @Transactional
    public String commitReceiveAtomic(String sessionId, String userId, String channel, double amount) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if (channel == null || channel.isBlank()) throw new IllegalArgumentException("channel is required");
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");

        String resolvedUserId = accountBalanceService.normalizeUserId(userId);
        double newBalance = accountBalanceService.creditBalance(resolvedUserId, amount);
        String txnId = "TXN-PASS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        TransactionRecord transaction = TransactionRecord.builder()
                .id(txnId)
                .sessionId(sessionId)
                .userId(resolvedUserId)
                .instructionType("PASS_MONEY_RECEIVE")
                .status("SETTLED")
                .createdAt(Instant.now())
                .resultingBalance(newBalance)
                .paymentMethod(channel)
                .requiresHitlReview(false)
                .financialData(FinancialData.builder()
                        .amount(amount)
                        .donor_account("External Payer")
                        .recipient_account(resolvedUserId)
                        .recipientBank(channel)
                        .merchantId("External Payer")
                        .build())
                .agentReasoningSnapshot(AgentReasoning.builder()
                        .supervisorDecision("APPROVED")
                        .contextSimilarityScore(0.95)
                        .build())
                .build();

        mongoTemplate.save(transaction);
        log.info("Receive committed: {} ₹{} via {} to user {} (balance ₹{})", txnId, amount, channel, resolvedUserId, newBalance);
        return txnId;
    }

    /**
     * Persists a mandate-switch instruction to the ledger (no money movement).
     * Used by the {@code switchMandate} tool so every agent-triggered mandate action is audited.
     */
    @Transactional
    public String commitMandateAtomic(String sessionId, String userId, String bankName, String mandateDetails) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        if (bankName == null || bankName.isBlank()) throw new IllegalArgumentException("bankName is required");

        String resolvedUserId = accountBalanceService.normalizeUserId(userId);
        double currentBalance = accountBalanceService.getCurrentBalance(resolvedUserId);
        String txnId = "TXN-PASS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        TransactionRecord record = TransactionRecord.builder()
                .id(txnId)
                .sessionId(sessionId)
                .userId(resolvedUserId)
                .instructionType("PASS_MANDATE_SWITCH")
                .status("SETTLED")
                .createdAt(Instant.now())
                .resultingBalance(currentBalance)
                .paymentMethod(bankName)
                .requiresHitlReview(false)
                .financialData(FinancialData.builder()
                        .amount(0.0)
                        .merchantId(bankName)
                        .recipientBank(bankName)
                        .donor_account(resolvedUserId)
                        .recipient_account(bankName)
                        .build())
                .agentReasoningSnapshot(AgentReasoning.builder()
                        .supervisorDecision("MANDATE_SWITCH_APPROVED")
                        .contextSimilarityScore(0.95)
                        .build())
                .build();

        mongoTemplate.save(record);
        log.info("Mandate switch committed: {} for user {} → bank {}", txnId, resolvedUserId, bankName);
        return txnId;
    }
}
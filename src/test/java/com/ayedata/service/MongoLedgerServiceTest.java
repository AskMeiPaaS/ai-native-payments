package com.ayedata.service;

import com.ayedata.domain.TransactionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoLedgerServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private AccountBalanceService accountBalanceService;

    @InjectMocks
    private MongoLedgerService mongoLedgerService;

    @Test
    void commitSwitchAtomic_withNullSessionId_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            mongoLedgerService.commitSwitchAtomic(null, "userId", "merchantId", "bank", 100.0));
        assertEquals("sessionId is required", exception.getMessage());
    }

    @Test
    void commitSwitchAtomic_withBlankSessionId_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            mongoLedgerService.commitSwitchAtomic("", "userId", "merchantId", "bank", 100.0));
        assertEquals("sessionId is required", exception.getMessage());
    }

    @Test
    void commitSwitchAtomic_withNullUserId_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            mongoLedgerService.commitSwitchAtomic("sessionId", null, "merchantId", "bank", 100.0));
        assertEquals("userId is required", exception.getMessage());
    }

    @Test
    void commitSwitchAtomic_withBlankUserId_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            mongoLedgerService.commitSwitchAtomic("sessionId", "", "merchantId", "bank", 100.0));
        assertEquals("userId is required", exception.getMessage());
    }

    @Test
    void commitSwitchAtomic_withNullMerchantId_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            mongoLedgerService.commitSwitchAtomic("sessionId", "userId", null, "bank", 100.0));
        assertEquals("beneficiary (name, account number, or merchant ID) is required", exception.getMessage());
    }

    @Test
    void commitSwitchAtomic_withBlankMerchantId_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            mongoLedgerService.commitSwitchAtomic("sessionId", "userId", "", "bank", 100.0));
        assertEquals("beneficiary (name, account number, or merchant ID) is required", exception.getMessage());
    }

    @Test
    void commitSwitchAtomic_withNullTargetBank_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            mongoLedgerService.commitSwitchAtomic("sessionId", "userId", "merchantId", null, 100.0));
        assertEquals("targetBank is required", exception.getMessage());
    }

    @Test
    void commitSwitchAtomic_withBlankTargetBank_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            mongoLedgerService.commitSwitchAtomic("sessionId", "userId", "merchantId", "", 100.0));
        assertEquals("targetBank is required", exception.getMessage());
    }

    @Test
    void commitSwitchAtomic_withZeroAmount_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            mongoLedgerService.commitSwitchAtomic("sessionId", "userId", "merchantId", "bank", 0.0));
        assertEquals("amount must be positive", exception.getMessage());
    }

    @Test
    void commitSwitchAtomic_withNegativeAmount_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            mongoLedgerService.commitSwitchAtomic("sessionId", "userId", "merchantId", "bank", -50.0));
        assertEquals("amount must be positive", exception.getMessage());
    }

    @Test
    void commitSwitchAtomic_withInsufficientBalance_throwsException() {
        when(accountBalanceService.normalizeUserId("userId")).thenReturn("userId");
        when(accountBalanceService.debitBalance("userId", 500.0))
                .thenThrow(new IllegalArgumentException("insufficient balance: available ₹200.00"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                mongoLedgerService.commitSwitchAtomic("sessionId", "userId", "merchantId", "bank", 500.0));

        assertTrue(exception.getMessage().contains("insufficient balance"));
    }

    @Test
    void commitSwitchAtomic_withValidInputs_persistsTransactionWithRemainingBalance() {
        when(accountBalanceService.normalizeUserId("userId")).thenReturn("userId");
        when(accountBalanceService.debitBalance("userId", 100.0)).thenReturn(900.0);

        assertDoesNotThrow(() ->
            mongoLedgerService.commitSwitchAtomic("sessionId", "userId", "merchantId", "bank", 100.0));

        ArgumentCaptor<TransactionRecord> transactionCaptor = ArgumentCaptor.forClass(TransactionRecord.class);
        verify(mongoTemplate).save(transactionCaptor.capture());

        TransactionRecord savedTransaction = transactionCaptor.getValue();
        assertEquals("userId", savedTransaction.getUserId());
        assertEquals("sessionId", savedTransaction.getSessionId());
        assertEquals(900.0, savedTransaction.getResultingBalance(), 0.001);
        assertEquals("SETTLED", savedTransaction.getStatus());
    }
}
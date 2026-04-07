package com.ayedata.service;

import com.ayedata.domain.FinancialData;
import com.ayedata.domain.TransactionRecord;
import com.ayedata.domain.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountBalanceServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private UserProfileEncryptionService encryptionService;

    private AccountBalanceService accountBalanceService;

    @BeforeEach
    void setUp() {
        accountBalanceService = new AccountBalanceService(mongoTemplate, encryptionService);
        lenient().when(mongoTemplate.save(any(UserProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // Encryption service returns the input unchanged (passthrough for tests)
        lenient().when(encryptionService.decrypt(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void debitBalance_withNewUser_createsDefaultBalanceAndDeducts() {
        when(mongoTemplate.findById("demo-user", UserProfile.class)).thenReturn(null);

        double remaining = accountBalanceService.debitBalance("demo-user", 500.0);

        assertEquals(24500.0, remaining, 0.001);
    }

    @Test
    void debitBalance_withInsufficientFunds_throwsException() {
        UserProfile profile = new UserProfile();
        profile.setId("demo-user");
        profile.setCurrentBalance(200.0);

        when(mongoTemplate.findById("demo-user", UserProfile.class)).thenReturn(profile);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> accountBalanceService.debitBalance("demo-user", 500.0));

        assertTrue(exception.getMessage().contains("insufficient balance"));
    }

    @Test
    void getDashboard_returnsLatestTransferSummary() {
        UserProfile profile = new UserProfile();
        profile.setId("demo-user");
        profile.setDisplayName("Demo User");
        profile.setCurrentBalance(23800.0);
        profile.setCurrency("INR");
        when(mongoTemplate.findById("demo-user", UserProfile.class)).thenReturn(profile);

        TransactionRecord latest = TransactionRecord.builder()
                .id("TXN-1")
                .userId("demo-user")
                .status("SETTLED")
                .resultingBalance(23800.0)
                .createdAt(Instant.now())
                .financialData(FinancialData.builder()
                        .amount(1200.0)
                        .merchantId("MERCHANT-1")
                        .recipientBank("ICICI")
                        .build())
                .build();

        when(mongoTemplate.find(any(Query.class), eq(TransactionRecord.class))).thenReturn(List.of(latest));

        Map<String, Object> dashboard = accountBalanceService.getDashboard("demo-user");

        assertEquals(23800.0, (Double) dashboard.get("currentBalance"), 0.001);
        assertEquals(1200.0, (Double) dashboard.get("lastTransferAmount"), 0.001);
        assertEquals("SETTLED", dashboard.get("lastTransferStatus"));
    }
}

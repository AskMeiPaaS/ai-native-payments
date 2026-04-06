package com.ayedata.ai.tools;

import com.ayedata.service.AccountBalanceService;
import com.ayedata.service.FraudContextService;
import com.ayedata.service.MongoLedgerService;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LedgerTools {
    private static final Logger log = LoggerFactory.getLogger(LedgerTools.class);

    private final FraudContextService fraudContextService;
    private final MongoLedgerService mongoLedgerService;
    private final AccountBalanceService accountBalanceService;

    public LedgerTools(FraudContextService fraudContextService,
                       MongoLedgerService mongoLedgerService,
                       AccountBalanceService accountBalanceService) {
        this.fraudContextService = fraudContextService;
        this.mongoLedgerService = mongoLedgerService;
        this.accountBalanceService = accountBalanceService;
    }

    @Tool("Transfers money securely to a beneficiary. The first parameter accepts a beneficiary name, account number, UPI ID, or merchant ID — use whatever the user provided. Use this for send/pay/transfer requests with a real amount. You must choose the payment channel or rail (e.g. UPI, NEFT, RTGS, IMPS) from the retrieved knowledge before calling this tool. The second parameter is the LLM-selected channel. It rejects overdrafts and never allows a negative balance.")
    public String transferFunds(String beneficiary, String targetBank, double amount) {
        log.info("Transfer tool invoked for beneficiary {} amount {} via {}", beneficiary, amount, targetBank);
        fraudContextService.evaluateTelemetryContext("{}",
                "Transfer ₹" + amount + " to " + beneficiary + " via " + targetBank);

        try {
            String txnId = mongoLedgerService.commitSwitchAtomic(
                    "tool-transfer-session",
                    AccountBalanceService.DEFAULT_USER_ID,
                    beneficiary,
                    targetBank,
                    amount
            );
            double remainingBalance = accountBalanceService.getCurrentBalance(AccountBalanceService.DEFAULT_USER_ID);
            return String.format(
                    "SUCCESS: ₹%.2f transferred to %s via %s. Reference: %s. Remaining balance: ₹%.2f.",
                    amount, beneficiary, targetBank, txnId, remainingBalance
            );
        } catch (IllegalArgumentException ex) {
            log.warn("Transfer blocked: {}", ex.getMessage());
            return "TRANSFER_BLOCKED: " + ex.getMessage() + ". Do not confirm the transfer.";
        }
    }

        @Tool("""
            Credits (adds) money into the user's account when they want to RECEIVE, ADD, TOP UP, DEPOSIT, or GET funds. \
            You must choose the 'channel' parameter from retrieved payment knowledge and user context before calling this tool. \
            Do not rely on hardcoded thresholds; use the current RAG knowledge to decide the channel. \
            Call this when the user says things like: 'add ₹5000 to my account', \
            'receive ₹10000', 'top up my wallet', 'deposit funds', 'credit my account', \
            'I got paid ₹X', or 'add balance'. \
            Never call this for outbound transfers — use transferFunds for those.\
            """)
    public String receiveFunds(double amount, String channel) {
        if (amount <= 0) {
            return "RECEIVE_BLOCKED: Amount must be positive.";
        }
        if (amount > 10_00_000.00) {
            return "RECEIVE_BLOCKED: Single credit cannot exceed ₹10,00,000.";
        }

        log.info("Receive funds tool invoked: ₹{} via {}", amount, channel);

        try {
            mongoLedgerService.commitReceiveAtomic(
                    "receive-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                    AccountBalanceService.DEFAULT_USER_ID, channel, amount);
            double newBalance = accountBalanceService.getCurrentBalance(AccountBalanceService.DEFAULT_USER_ID);
            return String.format(
                    "SUCCESS: ₹%.2f received via %s. Your new account balance is ₹%.2f.",
                    amount, channel, newBalance
            );
        } catch (IllegalArgumentException ex) {
            log.warn("Receive blocked: {}", ex.getMessage());
            return "RECEIVE_BLOCKED: " + ex.getMessage();
        }
    }

    @Tool("Registers a bank mandate or routing switch request without moving money. Use this only when no transfer amount is involved.")
    public String switchMandate(String bankName, String mandateDetails) {
        log.info("Mandate switch tool invoked for: {}", bankName);
        fraudContextService.evaluateTelemetryContext("{}", "Switch mandate to " + bankName);
        return "SUCCESS: Mandate switch initiated securely for " + bankName + ". Details: " + mandateDetails;
    }
}
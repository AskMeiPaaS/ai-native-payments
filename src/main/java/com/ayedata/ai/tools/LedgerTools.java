package com.ayedata.ai.tools;

import com.ayedata.init.UserProfileInitializer;
import com.ayedata.payment.PaymentContext;
import com.ayedata.payment.PaymentResult;
import com.ayedata.payment.PaymentSwitchRouter;
import com.ayedata.service.AccountBalanceService;
import com.ayedata.service.FraudContextService;
import com.ayedata.service.MongoLedgerService;
import com.ayedata.domain.FinancialData;
import com.ayedata.domain.TransactionRecord;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LedgerTools {
    private static final Logger log = LoggerFactory.getLogger(LedgerTools.class);

    /**
     * Session-scoped userId registry. Populated before orchestration, looked up
     * by {@code @ToolMemoryId} during tool execution (which can happen on any
     * thread — OkHttp callback, virtual thread, etc.).
     */
    private static final ConcurrentHashMap<String, String> sessionUserMap = new ConcurrentHashMap<>();

    private final FraudContextService fraudContextService;
    private final PaymentSwitchRouter paymentSwitchRouter;
    private final MongoLedgerService mongoLedgerService;
    private final AccountBalanceService accountBalanceService;
    private final MongoTemplate mongoTemplate;

    private static final DateTimeFormatter TXN_DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    public LedgerTools(FraudContextService fraudContextService,
                       PaymentSwitchRouter paymentSwitchRouter,
                       MongoLedgerService mongoLedgerService,
                       AccountBalanceService accountBalanceService,
                       @Qualifier("primaryMongoTemplate") MongoTemplate mongoTemplate) {
        this.fraudContextService = fraudContextService;
        this.paymentSwitchRouter = paymentSwitchRouter;
        this.mongoLedgerService = mongoLedgerService;
        this.accountBalanceService = accountBalanceService;
        this.mongoTemplate = mongoTemplate;
    }

    /** Register a session → userId mapping before orchestration starts. */
    public void registerSession(String sessionId, String userId) {
        sessionUserMap.put(sessionId, userId);
    }

    /** Remove the session mapping after orchestration completes. */
    public void unregisterSession(String sessionId) {
        sessionUserMap.remove(sessionId);
    }

    /**
     * Resolve the userId for the given session. Falls back to DEFAULT_USER_ID
     * only if the session was never registered (should not happen in normal flow).
     */
    private String resolveUserId(String sessionId) {
        String uid = sessionUserMap.get(sessionId);
        if (uid != null && !uid.isBlank()) return uid;
        log.warn("No userId registered for session {}; falling back to default", sessionId);
        return AccountBalanceService.DEFAULT_USER_ID;
    }

    @Tool("""
            Transfers money securely to a beneficiary. The first parameter accepts a beneficiary name, \
            account number, UPI ID, or merchant ID — use whatever the user provided. \
            Use this for send/pay/transfer requests with a real amount. \
            The channel parameter is optional — if not provided, the tool automatically selects the \
            optimal channel for the amount (UPI Lite ≤₹500, UPI ≤₹1L, NEFT <₹2L, RTGS ≥₹2L). \
            If the user has specified a channel, pass it as-is — the tool enforces all channel and \
            amount rules and will return CHANNEL_MISMATCH with a corrected channel if needed. \
            NEVER apply your own channel-amount rules. \
            On CHANNEL_MISMATCH: automatically re-call this tool with the suggested channel — \
            do NOT ask the user; just inform them of the correction you made. \
            It rejects overdrafts and never allows a negative balance.\
            """)
    public String transferFunds(@ToolMemoryId String memoryId,
                               @P("Beneficiary: person name, UPI ID, account number, or merchant ID") String beneficiary,
                               @P(value = "Payment channel (UPI Lite / UPI / NEFT / RTGS). Omit to auto-select.", required = false) String targetBank,
                               @P("Transfer amount in INR, must be positive") double amount) {
        String userId = resolveUserId(memoryId);

        // Auto-select the optimal channel when not explicitly provided
        if (targetBank == null || targetBank.isBlank()) {
            targetBank = selectChannelForAmount(amount);
            log.info("Transfer tool: auto-selected {} for ₹{} to {}", targetBank, amount, beneficiary);
        }

        // Validate channel against amount rules and give LLM a correction hint if needed
        String channelMismatch = validateChannelForAmount(targetBank, amount);
        if (channelMismatch != null) {
            log.info("Transfer channel mismatch: {} for ₹{} — {}", targetBank, amount, channelMismatch);
            return channelMismatch;
        }

        log.info("Transfer tool invoked: session={} user={} → beneficiary={} amount=₹{} via {}",
                memoryId, userId, beneficiary, amount, targetBank);
        fraudContextService.evaluateTelemetryContext("{}",
                "Transfer ₹" + amount + " to " + beneficiary + " via " + targetBank);

        try {
            // Resolve beneficiary to a registered user for P2P credit
            String recipientUserId = UserProfileInitializer.resolveUserIdByNameOrId(beneficiary);

            // Block self-transfers
            if (recipientUserId != null && recipientUserId.equalsIgnoreCase(userId)) {
                return "TRANSFER_BLOCKED: You cannot transfer money to yourself. Please specify a different beneficiary.";
            }

            PaymentContext ctx = new PaymentContext(memoryId, userId, beneficiary, amount, targetBank, recipientUserId);
            PaymentResult result = paymentSwitchRouter.route(targetBank).transfer(ctx);
            return String.format(
                    "SUCCESS: ₹%.2f transferred to %s via %s. Reference: %s. Remaining balance: ₹%.2f.",
                    amount, beneficiary, result.channel(), result.txnId(), result.resultingBalance()
            );
        } catch (IllegalArgumentException ex) {
            log.warn("Transfer blocked: {}", ex.getMessage());
            return "TRANSFER_BLOCKED: " + ex.getMessage() + ". Do not confirm the transfer.";
        }
    }

    @Tool("""
            Credits (adds) money into the user's account when they want to RECEIVE, ADD, TOP UP, DEPOSIT, or GET funds. \
            The channel parameter is optional — if not provided, the tool automatically selects the \
            optimal channel for the amount (UPI Lite ≤₹500, UPI ≤₹1L, NEFT <₹2L, RTGS ≥₹2L). \
            If the user has specified a channel, pass it as-is — the tool enforces all channel and amount \
            rules and will return CHANNEL_MISMATCH with a corrected channel name if needed. \
            NEVER apply your own channel-amount rules. \
            On CHANNEL_MISMATCH: automatically re-call this tool with the suggested channel — \
            do NOT ask the user; just inform them of the correction you made. \
            Call this when the user says things like: 'add ₹5000 to my account', \
            'receive ₹10000', 'top up my wallet', 'deposit funds', 'credit my account', \
            'I got paid ₹X', or 'add balance'. \
            Never call this for outbound transfers — use transferFunds for those.\
            """)
    public String receiveFunds(@ToolMemoryId String memoryId,
                              @P("Amount to credit in INR, must be positive") double amount,
                              @P(value = "Payment channel (UPI Lite / UPI / NEFT / RTGS). Omit to auto-select.", required = false) String channel) {
        if (amount <= 0) {
            return "RECEIVE_BLOCKED: Amount must be positive.";
        }
        if (amount > 10_00_000.00) {
            return "RECEIVE_BLOCKED: Single credit cannot exceed ₹10,00,000.";
        }

        String userId = resolveUserId(memoryId);

        // Auto-select the optimal channel when not explicitly provided
        if (channel == null || channel.isBlank()) {
            channel = selectChannelForAmount(amount);
            log.info("Receive tool: auto-selected {} for ₹{}", channel, amount);
        }

        // Validate channel against amount rules and give LLM a correction hint if needed
        String channelMismatch = validateChannelForAmount(channel, amount);
        if (channelMismatch != null) {
            log.info("Receive channel mismatch: {} for ₹{} — {}", channel, amount, channelMismatch);
            return channelMismatch;
        }

        log.info("Receive funds tool invoked: session={} user={} ₹{} via {}", memoryId, userId, amount, channel);

        try {
            PaymentContext ctx = new PaymentContext(memoryId, userId, "External Payer", amount, channel, null);
            PaymentResult result = paymentSwitchRouter.route(channel).receive(ctx);
            return String.format(
                    "SUCCESS: ₹%.2f received via %s. Your new account balance is ₹%.2f.",
                    amount, result.channel(), result.resultingBalance()
            );
        } catch (IllegalArgumentException ex) {
            log.warn("Receive blocked: {}", ex.getMessage());
            return "RECEIVE_BLOCKED: " + ex.getMessage();
        }
    }

    /**
     * Validate that the user-specified channel is legal for the given amount.
     * Returns null when the channel+amount combination is valid.
     * Returns a CHANNEL_MISMATCH string (consumed by the LLM) when correction is needed;
     * the message always names the corrected channel so the LLM can re-invoke automatically.
     */
    private String validateChannelForAmount(String channel, double amount) {
        String norm = channel.trim().toLowerCase();

        // UPI Lite — hard cap ₹500
        if (norm.equals("upi lite") || norm.equals("upilite") || norm.equals("upi_lite")) {
            if (amount > 500) {
                String fix = amount <= 1_00_000 ? "UPI" : (amount >= 2_00_000 ? "RTGS" : "NEFT");
                return String.format(
                    "CHANNEL_MISMATCH: UPI Lite supports a maximum of ₹500, but the amount is ₹%.2f. " +
                    "Corrected channel: %s. Re-invoke transferFunds or receiveFunds with channel=%s.",
                    amount, fix, fix);
            }
        }

        // UPI — hard cap ₹1,00,000
        if (norm.equals("upi")) {
            if (amount > 1_00_000) {
                String fix = amount >= 2_00_000 ? "RTGS" : "NEFT";
                return String.format(
                    "CHANNEL_MISMATCH: UPI supports a maximum of ₹1,00,000, but the amount is ₹%.2f. " +
                    "Corrected channel: %s. Re-invoke transferFunds or receiveFunds with channel=%s.",
                    amount, fix, fix);
            }
        }

        // RTGS — hard floor ₹2,00,000
        if (norm.equals("rtgs")) {
            if (amount < 2_00_000) {
                String fix = amount <= 500 ? "UPI Lite" : (amount <= 1_00_000 ? "UPI" : "NEFT");
                return String.format(
                    "CHANNEL_MISMATCH: RTGS requires a minimum of ₹2,00,000, but the amount is ₹%.2f. " +
                    "Corrected channel: %s. Re-invoke transferFunds or receiveFunds with channel=%s.",
                    amount, fix, fix);
            }
        }

        return null; // channel and amount are compatible
    }

    /**
     * Select the optimal payment channel for the given amount.
     * Mirrors the correction logic in {@link #validateChannelForAmount}.
     */
    private String selectChannelForAmount(double amount) {
        if (amount <= 500)       return "UPI Lite";
        if (amount <= 1_00_000)  return "UPI";
        if (amount < 2_00_000)   return "NEFT";
        return "RTGS";
    }

    @Tool("Registers a bank mandate or routing switch request without moving money. Use this only when no transfer amount is involved.")
    public String switchMandate(@ToolMemoryId String memoryId,
                               @P("Bank name or mandate target (e.g. 'HDFC Bank', 'SBI')") String bankName,
                               @P("Mandate details or routing instructions as provided by the user") String mandateDetails) {
        String userId = resolveUserId(memoryId);
        log.info("Mandate switch tool invoked for session {} user {}: {}", memoryId, userId, bankName);
        fraudContextService.evaluateTelemetryContext("{}", "Switch mandate to " + bankName);
        try {
            String txnId = mongoLedgerService.commitMandateAtomic(memoryId, userId, bankName, mandateDetails);
            return "SUCCESS: Mandate switch initiated securely for " + bankName + ". Reference: " + txnId + ". Details: " + mandateDetails;
        } catch (IllegalArgumentException ex) {
            log.warn("Mandate switch blocked: {}", ex.getMessage());
            return "MANDATE_BLOCKED: " + ex.getMessage();
        }
    }

    // ── Read-only query tools ──

    @Tool("""
            Returns the user's current account balance and account details. \
            Use this when the user asks: 'what is my balance', 'how much do I have', \
            'show my account', 'check balance', 'account details', etc.\
            """)
    public String checkBalance(@ToolMemoryId String memoryId) {
        String userId = resolveUserId(memoryId);
        log.info("Balance query tool invoked: session={} user={}", memoryId, userId);
        try {
            double balance = accountBalanceService.getCurrentBalance(userId);
            Map<String, String> registry = UserProfileInitializer.getDemoUserRegistry();
            String displayName = registry.getOrDefault(userId, userId);
            Map<String, String> accountRegistry = UserProfileInitializer.getDemoAccountRegistry();
            String accountNo = null;
            for (var entry : accountRegistry.entrySet()) {
                if (entry.getValue().equals(userId)) { accountNo = entry.getKey(); break; }
            }
            return String.format("BALANCE: %s (Account: %s) has a current balance of ₹%.2f INR.",
                    displayName, accountNo != null ? accountNo : "N/A", balance);
        } catch (Exception e) {
            log.warn("Balance query failed for user {}: {}", userId, e.getMessage());
            return "QUERY_ERROR: Unable to retrieve balance. " + e.getMessage();
        }
    }

    @Tool("""
            Returns the user's recent transactions (last 5 by default). \
            Use this when the user asks: 'show my transactions', 'recent payments', \
            'transaction history', 'what did I pay', 'last transactions', \
            'show my activity', 'payment history', etc.\
            """)
    public String recentTransactions(@ToolMemoryId String memoryId,
                                     @P(value = "Number of transactions to return (1-10). Defaults to 5.", required = false) Integer count) {
        String userId = resolveUserId(memoryId);
        int limit = (count != null && count >= 1 && count <= 10) ? count : 5;
        log.info("Transaction history tool invoked: session={} user={} limit={}", memoryId, userId, limit);
        try {
            Query query = new Query(Criteria.where("userId").is(userId))
                    .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                    .limit(limit);
            List<TransactionRecord> txns = mongoTemplate.find(query, TransactionRecord.class);

            if (txns.isEmpty()) {
                return "TRANSACTIONS: No transactions found for your account.";
            }

            Map<String, String> registry = UserProfileInitializer.getDemoUserRegistry();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("TRANSACTIONS: Showing %d most recent transaction(s):\n", txns.size()));
            for (int i = 0; i < txns.size(); i++) {
                TransactionRecord txn = txns.get(i);
                sb.append(formatTransaction(i + 1, txn, userId, registry));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Transaction query failed for user {}: {}", userId, e.getMessage());
            return "QUERY_ERROR: Unable to retrieve transactions. " + e.getMessage();
        }
    }

    @Tool("""
            Searches the user's transaction history for payments to a specific person or account. \
            Use this when the user asks: 'did I pay Priya', 'how much did I send to Arjun', \
            'payments to <name>', 'show transfers to <person>', etc.\
            """)
    public String searchTransactions(@ToolMemoryId String memoryId,
                                     @P("Name, userId, or account number of the person to search for") String searchTerm) {
        String userId = resolveUserId(memoryId);
        log.info("Transaction search tool invoked: session={} user={} search='{}'", memoryId, userId, searchTerm);

        if (searchTerm == null || searchTerm.isBlank()) {
            return "QUERY_ERROR: Please specify a person or account to search for.";
        }

        try {
            // Resolve the search term to a userId if possible
            String targetUserId = UserProfileInitializer.resolveUserIdByNameOrId(searchTerm);

            Query query = new Query(Criteria.where("userId").is(userId))
                    .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                    .limit(10);
            List<TransactionRecord> allTxns = mongoTemplate.find(query, TransactionRecord.class);

            Map<String, String> registry = UserProfileInitializer.getDemoUserRegistry();
            String searchUpper = searchTerm.toUpperCase();

            List<TransactionRecord> matched = allTxns.stream().filter(txn -> {
                FinancialData fd = txn.getFinancialData();
                if (fd == null) return false;
                // Match by resolved userId
                if (targetUserId != null) {
                    if (targetUserId.equalsIgnoreCase(fd.getRecipient_account())
                            || targetUserId.equalsIgnoreCase(fd.getDonor_account())
                            || targetUserId.equalsIgnoreCase(fd.getMerchantId())) {
                        return true;
                    }
                }
                // Match by name/text in merchantId or recipient
                String merchant = fd.getMerchantId() != null ? fd.getMerchantId().toUpperCase() : "";
                String recipient = fd.getRecipient_account() != null ? fd.getRecipient_account().toUpperCase() : "";
                String donor = fd.getDonor_account() != null ? fd.getDonor_account().toUpperCase() : "";
                return merchant.contains(searchUpper) || recipient.contains(searchUpper) || donor.contains(searchUpper);
            }).toList();

            if (matched.isEmpty()) {
                return String.format("TRANSACTIONS: No transactions found involving '%s'.", searchTerm);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("TRANSACTIONS: Found %d transaction(s) involving '%s':\n", matched.size(), searchTerm));
            for (int i = 0; i < matched.size(); i++) {
                sb.append(formatTransaction(i + 1, matched.get(i), userId, registry));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Transaction search failed for user {}: {}", userId, e.getMessage());
            return "QUERY_ERROR: Unable to search transactions. " + e.getMessage();
        }
    }

    /** Format a single transaction record as a numbered line for tool output. */
    private String formatTransaction(int index, TransactionRecord txn, String callerUserId, Map<String, String> registry) {
        FinancialData fd = txn.getFinancialData();
        String instrType = txn.getInstructionType();
        boolean isCredit = instrType != null && instrType.contains("RECEIVE");
        String type = isCredit ? "CREDIT" : "DEBIT";
        double amount = fd != null ? fd.getAmount() : 0;
        String channel = txn.getPaymentMethod() != null ? txn.getPaymentMethod() : "—";
        String date = txn.getCreatedAt() != null ? TXN_DATE_FMT.format(txn.getCreatedAt()) : "—";

        // Resolve counter-party to a display name
        String counterParty = "—";
        if (fd != null) {
            if (isCredit) {
                counterParty = resolveName(fd.getDonor_account(), callerUserId, registry);
            } else {
                String target = fd.getRecipient_account() != null ? fd.getRecipient_account() : fd.getMerchantId();
                counterParty = resolveName(target, callerUserId, registry);
            }
        }

        return String.format("%d. %s ₹%.2f %s %s via %s on %s (Ref: %s)\n",
                index, type, amount, isCredit ? "from" : "to", counterParty, channel, date, txn.getId());
    }

    /** Resolve a userId or name to a human display name. */
    private static String resolveName(String raw, String callerUserId, Map<String, String> registry) {
        if (raw == null || raw.isBlank()) return "—";
        if (raw.equalsIgnoreCase(callerUserId)) return "You";
        String name = registry.get(raw);
        return name != null ? name : raw;
    }
}
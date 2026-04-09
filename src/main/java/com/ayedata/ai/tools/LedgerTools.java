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
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class LedgerTools {
    private static final Logger log = LoggerFactory.getLogger(LedgerTools.class);

    /** MongoDB collection in {@code pass_memory} that maps sessionId → userId. */
    private static final String SESSION_REGISTRY = "session_registry";

    private final FraudContextService fraudContextService;
    private final PaymentSwitchRouter paymentSwitchRouter;
    private final MongoLedgerService mongoLedgerService;
    private final AccountBalanceService accountBalanceService;
    private final MongoTemplate mongoTemplate;
    private final MongoTemplate memoryMongoTemplate;

    private static final DateTimeFormatter TXN_DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    public LedgerTools(FraudContextService fraudContextService,
                       PaymentSwitchRouter paymentSwitchRouter,
                       MongoLedgerService mongoLedgerService,
                       AccountBalanceService accountBalanceService,
                       @Qualifier("primaryMongoTemplate") MongoTemplate mongoTemplate,
                       @Qualifier("memoryMongoTemplate") MongoTemplate memoryMongoTemplate) {
        this.fraudContextService = fraudContextService;
        this.paymentSwitchRouter = paymentSwitchRouter;
        this.mongoLedgerService = mongoLedgerService;
        this.accountBalanceService = accountBalanceService;
        this.mongoTemplate = mongoTemplate;
        this.memoryMongoTemplate = memoryMongoTemplate;
    }

    /** Ensure a 1-hour TTL index on session_registry so orphaned sessions are auto-cleaned. */
    @PostConstruct
    void ensureSessionRegistryTtl() {
        try {
            var collection = memoryMongoTemplate.getCollection(SESSION_REGISTRY);
            collection.createIndex(
                    new org.bson.Document("updatedAt", 1),
                    new com.mongodb.client.model.IndexOptions()
                            .name("session_registry_ttl")
                            .expireAfter(1L, TimeUnit.HOURS));
            log.info("✅ session_registry TTL index ensured (1 hour)");
        } catch (com.mongodb.MongoCommandException ex) {
            if (!ex.getMessage().contains("already exists")) {
                log.warn("session_registry TTL index creation issue: {}", ex.getMessage());
            }
        } catch (Exception e) {
            log.warn("session_registry TTL index creation skipped: {}", e.getMessage());
        }
    }

    /**
     * Register a session → userId mapping in MongoDB before orchestration starts.
     * Survives JVM restarts and works across multiple app replicas.
     */
    public void registerSession(String sessionId, String userId) {
        Query query = Query.query(Criteria.where("_id").is(sessionId));
        Update update = Update.update("userId", userId)
                .set("updatedAt", new Date());
        memoryMongoTemplate.upsert(query, update, SESSION_REGISTRY);
    }

    /** Remove the session mapping from MongoDB after orchestration completes. */
    public void unregisterSession(String sessionId) {
        memoryMongoTemplate.remove(
                Query.query(Criteria.where("_id").is(sessionId)), SESSION_REGISTRY);
    }

    /**
     * Resolve the userId for the given session from MongoDB. Falls back to
     * DEFAULT_USER_ID only if the session was never registered.
     */
    public String resolveUserId(String sessionId) {
        Query query = Query.query(Criteria.where("_id").is(sessionId));
        Document doc = memoryMongoTemplate.findOne(query, Document.class, SESSION_REGISTRY);
        if (doc != null) {
            String uid = doc.getString("userId");
            if (uid != null && !uid.isBlank()) return uid;
        }
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
            'show my activity', 'payment history', etc. \
            Use the type filter when the user asks for only credits/debits, e.g. \
            'show my credits', 'only debits', 'money received', 'money sent'.\
            """)
    public String recentTransactions(@ToolMemoryId String memoryId,
                                     @P(value = "Number of transactions to return (1-10). Defaults to 5.", required = false) Integer count,
                                     @P(value = "Filter by transaction type: 'credit' for received payments, 'debit' for sent payments. Omit for all.", required = false) String type) {
        String userId = resolveUserId(memoryId);
        int limit = (count != null && count >= 1 && count <= 10) ? count : 5;
        log.info("Transaction history tool invoked: session={} user={} limit={} type={}", memoryId, userId, limit, type);
        try {
            Criteria criteria = Criteria.where("userId").is(userId);
            if (type != null && !type.isBlank()) {
                String typeUpper = type.trim().toUpperCase();
                if (typeUpper.startsWith("CREDIT") || typeUpper.equals("RECEIVE") || typeUpper.equals("RECEIVED")) {
                    criteria = criteria.and("instructionType").is("PASS_MONEY_RECEIVE");
                } else if (typeUpper.startsWith("DEBIT") || typeUpper.equals("SEND") || typeUpper.equals("SENT") || typeUpper.equals("TRANSFER")) {
                    criteria = criteria.and("instructionType").is("PASS_MONEY_TRANSFER");
                }
            }
            Query query = new Query(criteria)
                    .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                    .limit(limit);
            List<TransactionRecord> txns = mongoTemplate.find(query, TransactionRecord.class);

            if (txns.isEmpty()) {
                return "TRANSACTIONS: No transactions found for your account.";
            }

            Map<String, String> registry = UserProfileInitializer.getDemoUserRegistry();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("TRANSACTIONS: Showing %d most recent transaction(s):\n", txns.size()));

            double totalDebits = 0;
            double totalCredits = 0;
            int debitCount = 0;
            int creditCount = 0;

            for (int i = 0; i < txns.size(); i++) {
                TransactionRecord txn = txns.get(i);
                sb.append(formatTransaction(i + 1, txn, userId, registry));

                String instrType = txn.getInstructionType();
                boolean isCredit = instrType != null && instrType.contains("RECEIVE");
                double amount = txn.getFinancialData() != null ? txn.getFinancialData().getAmount() : 0;
                if (isCredit) {
                    totalCredits += amount;
                    creditCount++;
                } else {
                    totalDebits += amount;
                    debitCount++;
                }
            }

            sb.append("\nSummary:");
            if (debitCount > 0) {
                sb.append(String.format(" Debits: %d × ₹%.2f.", debitCount, totalDebits));
            }
            if (creditCount > 0) {
                sb.append(String.format(" Credits: %d × ₹%.2f.", creditCount, totalCredits));
            }
            sb.append(String.format(" Net: ₹%.2f\n", totalCredits - totalDebits));

            return sb.toString();
        } catch (Exception e) {
            log.warn("Transaction query failed for user {}: {}", userId, e.getMessage());
            return "QUERY_ERROR: Unable to retrieve transactions. " + e.getMessage();
        }
    }

    @Tool("""
            Searches the user's transaction history for payments to a specific person or account. \
            Use this when the user asks: 'did I pay Priya', 'how much did I send to Arjun', \
            'payments to <name>', 'show transfers to <person>', etc. \
            Use the type filter to narrow by credit/debit direction.\
            """)
    public String searchTransactions(@ToolMemoryId String memoryId,
                                     @P("Name, userId, or account number of the person to search for") String searchTerm,
                                     @P(value = "Filter by transaction type: 'credit' for received, 'debit' for sent. Omit for all.", required = false) String type) {
        String userId = resolveUserId(memoryId);
        log.info("Transaction search tool invoked: session={} user={} search='{}' type={}", memoryId, userId, searchTerm, type);

        if (searchTerm == null || searchTerm.isBlank()) {
            return "QUERY_ERROR: Please specify a person or account to search for.";
        }

        try {
            // Resolve the search term to a userId if possible
            String targetUserId = UserProfileInitializer.resolveUserIdByNameOrId(searchTerm);

            Criteria criteria = Criteria.where("userId").is(userId);
            if (type != null && !type.isBlank()) {
                String typeUpper = type.trim().toUpperCase();
                if (typeUpper.startsWith("CREDIT") || typeUpper.equals("RECEIVE") || typeUpper.equals("RECEIVED")) {
                    criteria = criteria.and("instructionType").is("PASS_MONEY_RECEIVE");
                } else if (typeUpper.startsWith("DEBIT") || typeUpper.equals("SEND") || typeUpper.equals("SENT") || typeUpper.equals("TRANSFER")) {
                    criteria = criteria.and("instructionType").is("PASS_MONEY_TRANSFER");
                }
            }
            Query query = new Query(criteria)
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

            double totalDebits = 0;
            double totalCredits = 0;
            int debitCount = 0;
            int creditCount = 0;

            for (int i = 0; i < matched.size(); i++) {
                TransactionRecord txn = matched.get(i);
                sb.append(formatTransaction(i + 1, txn, userId, registry));

                // Accumulate totals
                String instrType = txn.getInstructionType();
                boolean isCredit = instrType != null && instrType.contains("RECEIVE");
                double amount = txn.getFinancialData() != null ? txn.getFinancialData().getAmount() : 0;
                if (isCredit) {
                    totalCredits += amount;
                    creditCount++;
                } else {
                    totalDebits += amount;
                    debitCount++;
                }
            }

            // Append accurate summary so downstream never has to guess
            sb.append("\nSummary:");
            if (debitCount > 0) {
                sb.append(String.format(" Debits: %d × ₹%.2f.", debitCount, totalDebits));
            }
            if (creditCount > 0) {
                sb.append(String.format(" Credits: %d × ₹%.2f.", creditCount, totalCredits));
            }
            sb.append(String.format(" Net: ₹%.2f\n", totalCredits - totalDebits));

            return sb.toString();
        } catch (Exception e) {
            log.warn("Transaction search failed for user {}: {}", userId, e.getMessage());
            return "QUERY_ERROR: Unable to search transactions. " + e.getMessage();
        }
    }

    // ── MongoDB Query Execution (called directly by orchestrator Step 2) ──

    /** Fields the LLM is allowed to include in a MongoDB query filter. */
    private static final Set<String> ALLOWED_FILTER_FIELDS =
            Set.of("instructionType", "paymentMethod", "status");

    /**
     * Execute a MongoDB query against the transactions collection using the
     * filter generated by the Step 1 classifier. The {@code userId} is always
     * injected (never trusting the LLM-supplied filter for identity).
     *
     * @param userId       authenticated user — forced into every query
     * @param filterJson   JSON filter from the classifier (e.g. {@code {"instructionType":"PASS_MONEY_RECEIVE"}})
     * @param searchTerm   optional counterparty name for client-side filtering (may be null)
     * @param limit        max documents to return
     * @return formatted transaction list or error string
     */
    public String executeMongoQuery(String userId, String filterJson, String searchTerm, int limit) {
        log.info("executeMongoQuery: userId={} filter={} search='{}' limit={}", userId, filterJson, searchTerm, limit);
        try {
            // Build criteria — userId is ALWAYS injected (security)
            Criteria criteria = Criteria.where("userId").is(userId);

            // Parse and apply whitelisted filter fields from the LLM-generated JSON
            if (filterJson != null && !filterJson.isBlank() && !filterJson.equals("{}")) {
                try {
                    Document filterDoc = Document.parse(filterJson);
                    for (var entry : filterDoc.entrySet()) {
                        if (ALLOWED_FILTER_FIELDS.contains(entry.getKey())) {
                            criteria = criteria.and(entry.getKey()).is(entry.getValue());
                        } else if ("createdAt".equals(entry.getKey()) && entry.getValue() instanceof Document dateDoc) {
                            // Support date range: {"createdAt": {"$gte": "7d"}} → last 7 days
                            String gte = dateDoc.getString("$gte");
                            if (gte != null && gte.matches("\\d+d")) {
                                int days = Integer.parseInt(gte.replace("d", ""));
                                criteria = criteria.and("createdAt").gte(Instant.now().minus(days, ChronoUnit.DAYS));
                            }
                        } else {
                            log.warn("executeMongoQuery: rejected non-whitelisted field '{}'", entry.getKey());
                        }
                    }
                } catch (Exception parseEx) {
                    log.warn("executeMongoQuery: filter parse failed '{}': {}", filterJson, parseEx.getMessage());
                    // Continue with userId-only criteria
                }
            }

            Query query = new Query(criteria)
                    .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                    .limit(limit);
            List<TransactionRecord> txns = mongoTemplate.find(query, TransactionRecord.class);

            // Optional client-side counterparty text search
            if (searchTerm != null && !searchTerm.isBlank() && !"none".equalsIgnoreCase(searchTerm)) {
                String targetUserId = UserProfileInitializer.resolveUserIdByNameOrId(searchTerm);
                String searchUpper = searchTerm.toUpperCase();
                txns = txns.stream().filter(txn -> {
                    FinancialData fd = txn.getFinancialData();
                    if (fd == null) return false;
                    if (targetUserId != null) {
                        if (targetUserId.equalsIgnoreCase(fd.getRecipient_account())
                                || targetUserId.equalsIgnoreCase(fd.getDonor_account())
                                || targetUserId.equalsIgnoreCase(fd.getMerchantId())) {
                            return true;
                        }
                    }
                    String merchant = fd.getMerchantId() != null ? fd.getMerchantId().toUpperCase() : "";
                    String recipient = fd.getRecipient_account() != null ? fd.getRecipient_account().toUpperCase() : "";
                    String donor = fd.getDonor_account() != null ? fd.getDonor_account().toUpperCase() : "";
                    return merchant.contains(searchUpper) || recipient.contains(searchUpper) || donor.contains(searchUpper);
                }).toList();
            }

            if (txns.isEmpty()) {
                return searchTerm != null && !searchTerm.isBlank() && !"none".equalsIgnoreCase(searchTerm)
                        ? String.format("TRANSACTIONS: No transactions found involving '%s'.", searchTerm)
                        : "TRANSACTIONS: No transactions found for your account.";
            }

            Map<String, String> registry = UserProfileInitializer.getDemoUserRegistry();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("TRANSACTIONS: Showing %d transaction(s):\n", txns.size()));

            double totalDebits = 0, totalCredits = 0;
            int debitCount = 0, creditCount = 0;

            for (int i = 0; i < txns.size(); i++) {
                TransactionRecord txn = txns.get(i);
                sb.append(formatTransaction(i + 1, txn, userId, registry));
                String instrType = txn.getInstructionType();
                boolean isCredit = instrType != null && instrType.contains("RECEIVE");
                double amount = txn.getFinancialData() != null ? txn.getFinancialData().getAmount() : 0;
                if (isCredit) { totalCredits += amount; creditCount++; }
                else { totalDebits += amount; debitCount++; }
            }

            sb.append("\nSummary:");
            if (debitCount > 0) sb.append(String.format(" Debits: %d × ₹%.2f.", debitCount, totalDebits));
            if (creditCount > 0) sb.append(String.format(" Credits: %d × ₹%.2f.", creditCount, totalCredits));
            sb.append(String.format(" Net: ₹%.2f\n", totalCredits - totalDebits));

            return sb.toString();
        } catch (Exception e) {
            log.warn("executeMongoQuery failed for user {}: {}", userId, e.getMessage());
            return "QUERY_ERROR: Unable to execute query. " + e.getMessage();
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
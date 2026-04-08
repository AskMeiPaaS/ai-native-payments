package com.ayedata.ai.tools;

import com.ayedata.init.UserProfileInitializer;
import com.ayedata.payment.PaymentContext;
import com.ayedata.payment.PaymentResult;
import com.ayedata.payment.PaymentSwitchRouter;
import com.ayedata.service.AccountBalanceService;
import com.ayedata.service.FraudContextService;
import com.ayedata.service.MongoLedgerService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    public LedgerTools(FraudContextService fraudContextService,
                       PaymentSwitchRouter paymentSwitchRouter,
                       MongoLedgerService mongoLedgerService) {
        this.fraudContextService = fraudContextService;
        this.paymentSwitchRouter = paymentSwitchRouter;
        this.mongoLedgerService = mongoLedgerService;
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
}
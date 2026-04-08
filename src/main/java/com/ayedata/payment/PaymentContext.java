package com.ayedata.payment;

/**
 * Immutable input to every payment channel switch.
 * Built by {@link com.ayedata.ai.tools.LedgerTools} and routed by {@link PaymentSwitchRouter}.
 *
 * @param sessionId       LangChain4j memory / audit correlation id
 * @param userId          resolved userId of the sender
 * @param beneficiary     beneficiary name, account number, UPI ID, or merchant ID
 * @param amount          transfer amount in INR (must be > 0)
 * @param channel         LLM-selected payment channel label (e.g. "UPI", "NEFT")
 * @param recipientUserId registered userId of the recipient for P2P credit; null for external payees
 */
public record PaymentContext(
        String sessionId,
        String userId,
        String beneficiary,
        double amount,
        String channel,
        String recipientUserId) {}

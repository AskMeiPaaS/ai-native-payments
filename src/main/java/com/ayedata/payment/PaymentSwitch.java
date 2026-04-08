package com.ayedata.payment;

import java.util.Set;

/**
 * Strategy interface for a payment channel switch.
 *
 * <p>The LLM acts as an agentic router — it selects the payment method from RAG-enriched context
 * and passes the canonical channel name to {@link PaymentSwitchRouter}. The router resolves the
 * matching {@code PaymentSwitch} implementation, which enforces channel-specific business rules
 * and delegates ACID ledger commits to {@link com.ayedata.service.MongoLedgerService}.
 *
 * <p>Each implementation is a Spring {@code @Component} so it is auto-discovered by the router.
 */
public interface PaymentSwitch {

    /**
     * Canonical display name for this channel (e.g. "UPI", "NEFT", "RTGS").
     * Used in transaction records and response messages.
     */
    String channel();

    /**
     * All lowercase aliases this switch accepts, including the canonical name.
     * {@link PaymentSwitchRouter} builds its lookup map from these sets.
     */
    Set<String> aliases();

    /**
     * Execute an outbound transfer for the given context.
     * Implementations perform channel-specific validation before committing.
     *
     * @throws IllegalArgumentException if the request violates channel rules (e.g. amount ceiling)
     */
    PaymentResult transfer(PaymentContext ctx);

    /**
     * Execute an inbound credit for the given context.
     * Implementations perform channel-specific validation (e.g. RTGS minimum).
     *
     * @throws IllegalArgumentException if the request violates channel rules
     */
    PaymentResult receive(PaymentContext ctx);
}

package com.ayedata.payment;

/**
 * Outcome returned by every payment channel switch after a successful transfer or receive.
 *
 * @param txnId            ledger reference (e.g. TXN-PASS-XXXXXXXX)
 * @param resultingBalance sender's account balance after the operation
 * @param channel          canonical channel name used for the transaction
 */
public record PaymentResult(
        String txnId,
        double resultingBalance,
        String channel) {}

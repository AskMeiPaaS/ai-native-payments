package com.ayedata.payment.channels;

import com.ayedata.payment.PaymentContext;
import com.ayedata.payment.PaymentResult;
import com.ayedata.payment.PaymentSwitch;
import com.ayedata.service.AccountBalanceService;
import com.ayedata.service.MongoLedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * RTGS payment switch.
 *
 * <p>Limits (RBI):
 * <ul>
 *   <li>Minimum transfer: ₹2,00,000 (mandatory RBI floor)</li>
 *   <li>Maximum: No statutory cap; bank-specific daily limits apply</li>
 *   <li>Settlement: real-time gross (individual, not batched), irrevocable</li>
 *   <li>Operating hours: 24×7 since Dec 2020</li>
 *   <li>AML screening mandatory above ₹10,00,000</li>
 * </ul>
 */
@Component
public class RtgsPaymentSwitch implements PaymentSwitch {

    private static final Logger log = LoggerFactory.getLogger(RtgsPaymentSwitch.class);
    static final double MIN_AMOUNT = 2_00_000.0;
    private static final double AML_THRESHOLD = 10_00_000.0;

    private final MongoLedgerService ledgerService;
    private final AccountBalanceService accountBalanceService;

    public RtgsPaymentSwitch(MongoLedgerService ledgerService,
                             AccountBalanceService accountBalanceService) {
        this.ledgerService = ledgerService;
        this.accountBalanceService = accountBalanceService;
    }

    @Override
    public String channel() { return "RTGS"; }

    @Override
    public Set<String> aliases() {
        return Set.of("rtgs");
    }

    @Override
    public PaymentResult transfer(PaymentContext ctx) {
        if (ctx.amount() < MIN_AMOUNT) {
            throw new IllegalArgumentException(String.format(
                    "RTGS minimum transfer is ₹%.0f. Use NEFT or UPI for smaller amounts.", MIN_AMOUNT));
        }
        if (ctx.amount() >= AML_THRESHOLD) {
            log.warn("[RTGS] AML screening required for ₹{} — above ₹10,00,000 threshold", ctx.amount());
        }
        log.info("[RTGS] Outbound ₹{} → {} for user {} (real-time gross, irrevocable)",
                ctx.amount(), ctx.beneficiary(), ctx.userId());
        String txnId = ledgerService.commitSwitchAtomic(
                ctx.sessionId(), ctx.userId(), ctx.beneficiary(), channel(), ctx.amount(), ctx.recipientUserId());
        double balance = accountBalanceService.getCurrentBalance(ctx.userId());
        return new PaymentResult(txnId, balance, channel());
    }

    @Override
    public PaymentResult receive(PaymentContext ctx) {
        log.info("[RTGS] Inbound ₹{} for user {} (real-time gross)", ctx.amount(), ctx.userId());
        String txnId = ledgerService.commitReceiveAtomic(ctx.sessionId(), ctx.userId(), channel(), ctx.amount());
        double balance = accountBalanceService.getCurrentBalance(ctx.userId());
        return new PaymentResult(txnId, balance, channel());
    }
}

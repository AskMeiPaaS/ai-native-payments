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
 * NEFT payment switch.
 *
 * <p>Limits (RBI):
 * <ul>
 *   <li>Minimum: ₹1 (no minimum enforced)</li>
 *   <li>Maximum: No statutory cap (RBI removed limit); bank-specific daily limits apply</li>
 *   <li>Settlement: batch, half-hourly (48 cycles/day), 24×7 since Dec 2019</li>
 *   <li>AML screening mandatory above ₹10,00,000</li>
 * </ul>
 */
@Component
public class NeftPaymentSwitch implements PaymentSwitch {

    private static final Logger log = LoggerFactory.getLogger(NeftPaymentSwitch.class);
    private static final double AML_THRESHOLD = 10_00_000.0;

    private final MongoLedgerService ledgerService;
    private final AccountBalanceService accountBalanceService;

    public NeftPaymentSwitch(MongoLedgerService ledgerService,
                             AccountBalanceService accountBalanceService) {
        this.ledgerService = ledgerService;
        this.accountBalanceService = accountBalanceService;
    }

    @Override
    public String channel() { return "NEFT"; }

    @Override
    public Set<String> aliases() {
        return Set.of("neft");
    }

    @Override
    public PaymentResult transfer(PaymentContext ctx) {
        if (ctx.amount() >= AML_THRESHOLD) {
            log.warn("[NEFT] AML screening required for ₹{} — above ₹10,00,000 threshold", ctx.amount());
        }
        log.info("[NEFT] Outbound ₹{} → {} for user {} (batch settlement: 30min–2hr)",
                ctx.amount(), ctx.beneficiary(), ctx.userId());
        String txnId = ledgerService.commitSwitchAtomic(
                ctx.sessionId(), ctx.userId(), ctx.beneficiary(), channel(), ctx.amount(), ctx.recipientUserId());
        double balance = accountBalanceService.getCurrentBalance(ctx.userId());
        return new PaymentResult(txnId, balance, channel());
    }

    @Override
    public PaymentResult receive(PaymentContext ctx) {
        log.info("[NEFT] Inbound ₹{} for user {} (batch settlement: 30min–2hr)", ctx.amount(), ctx.userId());
        String txnId = ledgerService.commitReceiveAtomic(ctx.sessionId(), ctx.userId(), channel(), ctx.amount());
        double balance = accountBalanceService.getCurrentBalance(ctx.userId());
        return new PaymentResult(txnId, balance, channel());
    }
}

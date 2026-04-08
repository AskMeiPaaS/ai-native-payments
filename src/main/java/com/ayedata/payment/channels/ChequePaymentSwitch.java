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
 * Cheque payment switch.
 *
 * <p>Rules (RBI / NI Act):
 * <ul>
 *   <li>Positive Pay System mandatory for cheques ≥ ₹50,000 (RBI Jan 2021)</li>
 *   <li>No statutory minimum or maximum amount</li>
 *   <li>CTS clearing: T+1 (same grid), T+2 (inter-grid)</li>
 *   <li>Inherently high-risk: recommend UPI/NEFT as safer alternatives below ₹50,000</li>
 *   <li>Cheques above ₹5,00,000: strongly recommend RTGS or NEFT</li>
 * </ul>
 */
@Component
public class ChequePaymentSwitch implements PaymentSwitch {

    private static final Logger log = LoggerFactory.getLogger(ChequePaymentSwitch.class);
    private static final double POSITIVE_PAY_THRESHOLD = 50_000.0;
    private static final double HIGH_VALUE_THRESHOLD = 5_00_000.0;

    private final MongoLedgerService ledgerService;
    private final AccountBalanceService accountBalanceService;

    public ChequePaymentSwitch(MongoLedgerService ledgerService,
                               AccountBalanceService accountBalanceService) {
        this.ledgerService = ledgerService;
        this.accountBalanceService = accountBalanceService;
    }

    @Override
    public String channel() { return "Cheque"; }

    @Override
    public Set<String> aliases() {
        return Set.of("cheque", "check", "chq");
    }

    @Override
    public PaymentResult transfer(PaymentContext ctx) {
        if (ctx.amount() >= POSITIVE_PAY_THRESHOLD) {
            log.warn("[Cheque] Positive Pay System required — pre-register cheque details with bank (amount ₹{})",
                    ctx.amount());
        }
        if (ctx.amount() >= HIGH_VALUE_THRESHOLD) {
            log.warn("[Cheque] High-value cheque ₹{}: RTGS or NEFT strongly recommended for faster, safer settlement",
                    ctx.amount());
        }
        log.info("[Cheque] Outbound ₹{} → {} for user {} (CTS clearing T+1/T+2)",
                ctx.amount(), ctx.beneficiary(), ctx.userId());
        String txnId = ledgerService.commitSwitchAtomic(
                ctx.sessionId(), ctx.userId(), ctx.beneficiary(), channel(), ctx.amount(), ctx.recipientUserId());
        double balance = accountBalanceService.getCurrentBalance(ctx.userId());
        return new PaymentResult(txnId, balance, channel());
    }

    @Override
    public PaymentResult receive(PaymentContext ctx) {
        log.info("[Cheque] Inbound ₹{} for user {} — note: funds subject to CTS clearing (T+1/T+2)",
                ctx.amount(), ctx.userId());
        String txnId = ledgerService.commitReceiveAtomic(ctx.sessionId(), ctx.userId(), channel(), ctx.amount());
        double balance = accountBalanceService.getCurrentBalance(ctx.userId());
        return new PaymentResult(txnId, balance, channel());
    }
}

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
 * Net Banking / IMPS payment switch.
 *
 * <p>Covers bank-initiated internet banking and IMPS (Immediate Payment Service):
 * <ul>
 *   <li>IMPS: instant 24×7, typically ₹1 – ₹5,00,000 per transaction</li>
 *   <li>Net Banking: bank-specific limits (typically ₹10,00,000 – ₹25,00,000/day)</li>
 *   <li>No statutory minimum or maximum set by RBI for net banking</li>
 *   <li>Requires account number and IFSC (or VPA for IMPS)</li>
 * </ul>
 */
@Component
public class NetBankingPaymentSwitch implements PaymentSwitch {

    private static final Logger log = LoggerFactory.getLogger(NetBankingPaymentSwitch.class);
    private static final double AML_THRESHOLD = 10_00_000.0;

    private final MongoLedgerService ledgerService;
    private final AccountBalanceService accountBalanceService;

    public NetBankingPaymentSwitch(MongoLedgerService ledgerService,
                                   AccountBalanceService accountBalanceService) {
        this.ledgerService = ledgerService;
        this.accountBalanceService = accountBalanceService;
    }

    @Override
    public String channel() { return "Net Banking"; }

    @Override
    public Set<String> aliases() {
        return Set.of("net banking", "netbanking", "net_banking", "internet banking",
                "internetbanking", "imps");
    }

    @Override
    public PaymentResult transfer(PaymentContext ctx) {
        if (ctx.amount() >= AML_THRESHOLD) {
            log.warn("[Net Banking] AML screening recommended for ₹{}", ctx.amount());
        }
        log.info("[Net Banking] Outbound ₹{} → {} for user {}", ctx.amount(), ctx.beneficiary(), ctx.userId());
        String txnId = ledgerService.commitSwitchAtomic(
                ctx.sessionId(), ctx.userId(), ctx.beneficiary(), channel(), ctx.amount(), ctx.recipientUserId());
        double balance = accountBalanceService.getCurrentBalance(ctx.userId());
        return new PaymentResult(txnId, balance, channel());
    }

    @Override
    public PaymentResult receive(PaymentContext ctx) {
        log.info("[Net Banking] Inbound ₹{} for user {}", ctx.amount(), ctx.userId());
        String txnId = ledgerService.commitReceiveAtomic(ctx.sessionId(), ctx.userId(), channel(), ctx.amount());
        double balance = accountBalanceService.getCurrentBalance(ctx.userId());
        return new PaymentResult(txnId, balance, channel());
    }
}

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
 * UPI payment switch.
 *
 * <p>Limits (RBI / NPCI):
 * <ul>
 *   <li>Standard P2P: ₹1,00,000 per transaction</li>
 *   <li>Verified P2M / capital markets / insurance: ₹2,00,000</li>
 *   <li>IPO, tax, hospital, education: up to ₹5,00,000 (enhanced category)</li>
 *   <li>Daily aggregate P2P: ₹1,00,000 across all transactions</li>
 *   <li>Settlement: instant, 24×7×365</li>
 * </ul>
 */
@Component
public class UpiPaymentSwitch implements PaymentSwitch {

    private static final Logger log = LoggerFactory.getLogger(UpiPaymentSwitch.class);
    static final double MAX_STANDARD_P2P = 1_00_000.0;

    private final MongoLedgerService ledgerService;
    private final AccountBalanceService accountBalanceService;

    public UpiPaymentSwitch(MongoLedgerService ledgerService,
                            AccountBalanceService accountBalanceService) {
        this.ledgerService = ledgerService;
        this.accountBalanceService = accountBalanceService;
    }

    @Override
    public String channel() { return "UPI"; }

    @Override
    public Set<String> aliases() {
        return Set.of("upi");
    }

    @Override
    public PaymentResult transfer(PaymentContext ctx) {
        if (ctx.amount() > MAX_STANDARD_P2P) {
            throw new IllegalArgumentException(String.format(
                    "UPI standard P2P limit is ₹%.0f per transaction. Use NEFT or RTGS for higher amounts.",
                    MAX_STANDARD_P2P));
        }
        log.info("[UPI] Outbound ₹{} → {} for user {}", ctx.amount(), ctx.beneficiary(), ctx.userId());
        String txnId = ledgerService.commitSwitchAtomic(
                ctx.sessionId(), ctx.userId(), ctx.beneficiary(), channel(), ctx.amount(), ctx.recipientUserId());
        double balance = accountBalanceService.getCurrentBalance(ctx.userId());
        return new PaymentResult(txnId, balance, channel());
    }

    @Override
    public PaymentResult receive(PaymentContext ctx) {
        log.info("[UPI] Inbound ₹{} for user {}", ctx.amount(), ctx.userId());
        String txnId = ledgerService.commitReceiveAtomic(ctx.sessionId(), ctx.userId(), channel(), ctx.amount());
        double balance = accountBalanceService.getCurrentBalance(ctx.userId());
        return new PaymentResult(txnId, balance, channel());
    }
}

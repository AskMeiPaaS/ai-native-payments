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
 * UPI Lite payment switch.
 *
 * <p>Limits (RBI / NPCI):
 * <ul>
 *   <li>Maximum per transaction: ₹500</li>
 *   <li>Wallet balance cap: ₹2,000</li>
 *   <li>No PIN required — fastest channel for micro-payments</li>
 * </ul>
 */
@Component
public class UpiLitePaymentSwitch implements PaymentSwitch {

    private static final Logger log = LoggerFactory.getLogger(UpiLitePaymentSwitch.class);
    static final double MAX_TXN_AMOUNT = 500.0;

    private final MongoLedgerService ledgerService;
    private final AccountBalanceService accountBalanceService;

    public UpiLitePaymentSwitch(MongoLedgerService ledgerService,
                                AccountBalanceService accountBalanceService) {
        this.ledgerService = ledgerService;
        this.accountBalanceService = accountBalanceService;
    }

    @Override
    public String channel() { return "UPI Lite"; }

    @Override
    public Set<String> aliases() {
        return Set.of("upi lite", "upilite", "upi_lite");
    }

    @Override
    public PaymentResult transfer(PaymentContext ctx) {
        if (ctx.amount() > MAX_TXN_AMOUNT) {
            throw new IllegalArgumentException(String.format(
                    "UPI Lite maximum is ₹%.0f per transaction. Use UPI for higher amounts.", MAX_TXN_AMOUNT));
        }
        log.info("[UPI Lite] Outbound ₹{} → {} for user {}", ctx.amount(), ctx.beneficiary(), ctx.userId());
        String txnId = ledgerService.commitSwitchAtomic(
                ctx.sessionId(), ctx.userId(), ctx.beneficiary(), channel(), ctx.amount(), ctx.recipientUserId());
        double balance = accountBalanceService.getCurrentBalance(ctx.userId());
        return new PaymentResult(txnId, balance, channel());
    }

    @Override
    public PaymentResult receive(PaymentContext ctx) {
        if (ctx.amount() > MAX_TXN_AMOUNT) {
            throw new IllegalArgumentException(String.format(
                    "UPI Lite maximum credit is ₹%.0f. Use UPI for higher amounts.", MAX_TXN_AMOUNT));
        }
        log.info("[UPI Lite] Inbound ₹{} for user {}", ctx.amount(), ctx.userId());
        String txnId = ledgerService.commitReceiveAtomic(ctx.sessionId(), ctx.userId(), channel(), ctx.amount());
        double balance = accountBalanceService.getCurrentBalance(ctx.userId());
        return new PaymentResult(txnId, balance, channel());
    }
}

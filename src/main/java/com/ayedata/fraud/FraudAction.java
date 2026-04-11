package com.ayedata.fraud;

/**
 * 🚨 Fraud Action Thresholds:
 * <pre>
 * Risk Score      Action      Description
 * ≥ 0.90         APPROVE     Autonomous execution
 * 0.80–0.89      MONITOR     Execute but alert compliance
 * 0.65–0.79      ESCALATE    Route to HITL operator (transaction frozen)
 * < 0.65         BLOCK       Transaction permanently stopped
 * Hardblock      BLOCK       Automatic rejection (geo-anomaly, new device)
 * </pre>
 */
public enum FraudAction {
    APPROVE,
    MONITOR,
    ESCALATE,
    BLOCK
}

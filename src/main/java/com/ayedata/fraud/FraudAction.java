package com.ayedata.fraud;

/**
 * 🚨 Fraud Action Thresholds (high risk score = more dangerous):
 * <pre>
 * Risk Score      Action      Description
 * < 0.30         APPROVE     Autonomous execution (low risk)
 * 0.30–0.49      MONITOR     Execute but alert compliance
 * 0.50–0.69      ESCALATE    Route to HITL operator (transaction frozen)
 * ≥ 0.70         BLOCK       Transaction permanently stopped (high risk)
 * Hardblock      BLOCK       Automatic rejection (geo-anomaly, new device)
 * </pre>
 */
public enum FraudAction {
    APPROVE,
    MONITOR,
    ESCALATE,
    BLOCK
}

package com.ayedata.fraud;

/**
 * 🚨 Fraud Action Thresholds:
 * <pre>
 * Risk Score      Action      Description
 * ≥ 0.95         APPROVE     Autonomous execution
 * 0.80–0.95      MONITOR     Execute but alert compliance
 * < 0.80         ESCALATE    Route to HITL operator
 * Any + BLOCK    BLOCK       Automatic rejection
 * </pre>
 */
public enum FraudAction {
    APPROVE,
    MONITOR,
    ESCALATE,
    BLOCK
}

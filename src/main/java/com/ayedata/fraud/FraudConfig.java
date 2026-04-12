package com.ayedata.fraud;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
public class FraudConfig {

    @Value("${app.fraud.high-value-threshold:5000}")
    private double highValueThreshold;

    @Value("${app.fraud.penalty.high-value:0.02}")
    private double penaltyHighValue;

    @Value("${app.fraud.penalty.geo-anomaly:0.10}")
    private double penaltyGeoAnomaly;

    @Value("${app.fraud.penalty.new-device:0.10}")
    private double penaltyNewDevice;

    @Value("${app.fraud.penalty.unusual-timing:0.03}")
    private double penaltyUnusualTiming;

    @Value("${app.fraud.threshold.block:0.70}")
    private double thresholdBlock;

    @Value("${app.fraud.threshold.escalate:0.50}")
    private double thresholdEscalate;

    @Value("${app.fraud.threshold.monitor:0.30}")
    private double thresholdMonitor;

    @Value("${app.fraud.baseline-score:0.02}")
    private double baselineScore;

    @Value("${app.fraud.behavioral.risk-weight:0.08}")
    private double behavioralRiskWeight;

    @Value("${app.fraud.hardblock-signals:GEO_ANOMALY_DETECTED,NEW_DEVICE_PATTERN}")
    private String hardblockSignalsCsv;

    @Value("${app.fraud.behavioral.lookback-days:90}")
    private int behavioralLookbackDays;

    @Value("${app.fraud.behavioral.min-transactions:3}")
    private int behavioralMinTransactions;

    @Value("${app.fraud.behavioral.query-limit:50}")
    private int behavioralQueryLimit;

    @Value("${app.fraud.behavioral.cv-divisor:2.0}")
    private double behavioralCvDivisor;

    @Value("${app.fraud.behavioral.frequency-norm:2.0}")
    private double behavioralFrequencyNorm;

    @Value("${app.fraud.behavioral.weight-amount:0.6}")
    private double behavioralWeightAmount;

    @Value("${app.fraud.behavioral.weight-frequency:0.4}")
    private double behavioralWeightFrequency;

    @Value("${app.fraud.rag-top-k:2}")
    private int fraudRagTopK;

    @Value("${app.fraud.agent-memory-window:4}")
    private int agentMemoryWindow;

    public double getHighValueThreshold() { return highValueThreshold; }
    public double getThresholdBlock() { return thresholdBlock; }
    public double getThresholdEscalate() { return thresholdEscalate; }
    public double getThresholdMonitor() { return thresholdMonitor; }
    public double getBaselineScore() { return baselineScore; }
    public double getBehavioralRiskWeight() { return behavioralRiskWeight; }
    public int getBehavioralLookbackDays() { return behavioralLookbackDays; }
    public int getBehavioralMinTransactions() { return behavioralMinTransactions; }
    public int getBehavioralQueryLimit() { return behavioralQueryLimit; }
    public double getBehavioralCvDivisor() { return behavioralCvDivisor; }
    public double getBehavioralFrequencyNorm() { return behavioralFrequencyNorm; }
    public double getBehavioralWeightAmount() { return behavioralWeightAmount; }
    public double getBehavioralWeightFrequency() { return behavioralWeightFrequency; }
    public int getFraudRagTopK() { return fraudRagTopK; }
    public int getAgentMemoryWindow() { return agentMemoryWindow; }

    public Set<String> getHardblockSignals() {
        return Arrays.stream(hardblockSignalsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public double getPenaltyForSignal(String signal) {
        return switch (signal) {
            case "HIGH_VALUE_TRANSACTION" -> penaltyHighValue;
            case "GEO_ANOMALY_DETECTED" -> penaltyGeoAnomaly;
            case "NEW_DEVICE_PATTERN" -> penaltyNewDevice;
            case "UNUSUAL_TIMING" -> penaltyUnusualTiming;
            default -> 0.0;
        };
    }
}

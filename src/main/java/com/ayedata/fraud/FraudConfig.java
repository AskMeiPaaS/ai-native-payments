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

    @Value("${app.fraud.multiplier.high-value:0.8}")
    private double multiplierHighValue;

    @Value("${app.fraud.multiplier.geo-anomaly:0.7}")
    private double multiplierGeoAnomaly;

    @Value("${app.fraud.multiplier.new-device:0.6}")
    private double multiplierNewDevice;

    @Value("${app.fraud.multiplier.unusual-timing:0.9}")
    private double multiplierUnusualTiming;

    @Value("${app.fraud.threshold.approve:0.90}")
    private double thresholdApprove;

    @Value("${app.fraud.threshold.monitor:0.80}")
    private double thresholdMonitor;

    @Value("${app.fraud.threshold.escalate:0.65}")
    private double thresholdEscalate;

    @Value("${app.fraud.threshold.block:0.65}")
    private double thresholdBlock;

    @Value("${app.fraud.baseline-score:0.95}")
    private double baselineScore;

    @Value("${app.fraud.hardblock-signals:GEO_ANOMALY_DETECTED,NEW_DEVICE_PATTERN}")
    private String hardblockSignalsCsv;

    @Value("${app.fraud.behavioral.lookback-days:90}")
    private int behavioralLookbackDays;

    @Value("${app.fraud.behavioral.min-transactions:3}")
    private int behavioralMinTransactions;

    public double getHighValueThreshold() { return highValueThreshold; }
    public double getMultiplierHighValue() { return multiplierHighValue; }
    public double getMultiplierGeoAnomaly() { return multiplierGeoAnomaly; }
    public double getMultiplierNewDevice() { return multiplierNewDevice; }
    public double getMultiplierUnusualTiming() { return multiplierUnusualTiming; }
    public double getThresholdApprove() { return thresholdApprove; }
    public double getThresholdMonitor() { return thresholdMonitor; }
    public double getThresholdEscalate() { return thresholdEscalate; }
    public double getThresholdBlock() { return thresholdBlock; }
    public double getBaselineScore() { return baselineScore; }
    public int getBehavioralLookbackDays() { return behavioralLookbackDays; }
    public int getBehavioralMinTransactions() { return behavioralMinTransactions; }

    public Set<String> getHardblockSignals() {
        return Arrays.stream(hardblockSignalsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public double getMultiplierForSignal(String signal) {
        return switch (signal) {
            case "HIGH_VALUE_TRANSACTION" -> multiplierHighValue;
            case "GEO_ANOMALY_DETECTED" -> multiplierGeoAnomaly;
            case "NEW_DEVICE_PATTERN" -> multiplierNewDevice;
            case "UNUSUAL_TIMING" -> multiplierUnusualTiming;
            default -> 1.0;
        };
    }
}

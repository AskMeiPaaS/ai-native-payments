package com.ayedata.fraud;

import java.util.List;

public record FraudAnalysisResult(
    double riskScore,
    double behavioralScore,
    List<String> fraudSignals,
    FraudAction action,
    String ragContext
) {}

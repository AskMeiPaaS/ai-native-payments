package com.ayedata.fraud;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudSignalAnalyzerTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private FraudConfig fraudConfig;

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private FraudSignalAnalyzer fraudSignalAnalyzer;

    @BeforeEach
    void setUp() {
        Embedding mockEmbedding = new Embedding(new float[]{0.1f, 0.2f, 0.3f});
        Response<Embedding> mockResponse = Response.from(mockEmbedding);
        lenient().when(embeddingModel.embed(anyString())).thenReturn(mockResponse);

        // Default config values matching application.properties defaults
        lenient().when(fraudConfig.getHighValueThreshold()).thenReturn(5000.0);
        lenient().when(fraudConfig.getBaselineScore()).thenReturn(0.95);
        lenient().when(fraudConfig.getThresholdApprove()).thenReturn(0.95);
        lenient().when(fraudConfig.getThresholdMonitor()).thenReturn(0.80);
        lenient().when(fraudConfig.getHardblockSignals()).thenReturn(Set.of("GEO_ANOMALY_DETECTED", "NEW_DEVICE_PATTERN"));
        lenient().when(fraudConfig.getMultiplierForSignal("HIGH_VALUE_TRANSACTION")).thenReturn(0.8);
        lenient().when(fraudConfig.getMultiplierForSignal("GEO_ANOMALY_DETECTED")).thenReturn(0.7);
        lenient().when(fraudConfig.getMultiplierForSignal("NEW_DEVICE_PATTERN")).thenReturn(0.6);
        lenient().when(fraudConfig.getMultiplierForSignal("UNUSUAL_TIMING")).thenReturn(0.9);
        lenient().when(fraudConfig.getBehavioralLookbackDays()).thenReturn(90);
        lenient().when(fraudConfig.getBehavioralMinTransactions()).thenReturn(3);
    }

    @Test
    void getBehavioralSimilarityScore_withNullSessionId_returnsZero() {
        double result = fraudSignalAnalyzer.getBehavioralSimilarityScore(null, "validUserId");
        assertEquals(0.0, result);
    }

    @Test
    void getBehavioralSimilarityScore_withBlankSessionId_returnsZero() {
        double result = fraudSignalAnalyzer.getBehavioralSimilarityScore("", "validUserId");
        assertEquals(0.0, result);
    }

    @Test
    void getBehavioralSimilarityScore_withNullUserId_returnsZero() {
        double result = fraudSignalAnalyzer.getBehavioralSimilarityScore("validSession", null);
        assertEquals(0.0, result);
    }

    @Test
    void getBehavioralSimilarityScore_withBlankUserId_returnsZero() {
        double result = fraudSignalAnalyzer.getBehavioralSimilarityScore("validSession", "");
        assertEquals(0.0, result);
    }

    @Test
    void getBehavioralSimilarityScore_withInvalidUserIdFormat_returnsZero() {
        double result = fraudSignalAnalyzer.getBehavioralSimilarityScore("validSession", "invalid@user!id");
        assertEquals(0.0, result);
    }

    @Test
    void getBehavioralSimilarityScore_withTooShortUserId_returnsZero() {
        double result = fraudSignalAnalyzer.getBehavioralSimilarityScore("validSession", "123");
        assertEquals(0.0, result);
    }

    @Test
    void getBehavioralSimilarityScore_withTooLongUserId_returnsZero() {
        String longUserId = "a".repeat(65);
        double result = fraudSignalAnalyzer.getBehavioralSimilarityScore("validSession", longUserId);
        assertEquals(0.0, result);
    }

    @Test
    void getBehavioralSimilarityScore_withValidInputs_returnsZeroWhenNoResults() {
        double result = fraudSignalAnalyzer.getBehavioralSimilarityScore("validSession", "validUserId123");
        assertEquals(0.0, result);
    }

    // ── Risk score computation tests ──

    @Test
    void computeRiskScore_withNoSignals_returns095Baseline() {
        double score = fraudSignalAnalyzer.computeRiskScore(0.0, java.util.List.of());
        assertEquals(0.95, score, 0.001);
    }

    @Test
    void computeRiskScore_withHighValueSignal_applies08Penalty() {
        double score = fraudSignalAnalyzer.computeRiskScore(0.0, java.util.List.of("HIGH_VALUE_TRANSACTION"));
        assertEquals(0.76, score, 0.001); // 0.95 * 0.8
    }

    @Test
    void computeRiskScore_withRealBehavioralScore_usesIt() {
        double score = fraudSignalAnalyzer.computeRiskScore(0.90, java.util.List.of());
        assertEquals(0.90, score, 0.001);
    }

    // ── Action determination tests ──

    @Test
    void determineFraudAction_approve_whenRiskAbove095() {
        assertEquals(FraudAction.APPROVE, fraudSignalAnalyzer.determineFraudAction(0.96, java.util.List.of()));
    }

    @Test
    void determineFraudAction_monitor_whenRiskBetween080And095() {
        assertEquals(FraudAction.MONITOR, fraudSignalAnalyzer.determineFraudAction(0.85, java.util.List.of()));
    }

    @Test
    void determineFraudAction_escalate_whenRiskBelow080() {
        assertEquals(FraudAction.ESCALATE, fraudSignalAnalyzer.determineFraudAction(0.50, java.util.List.of()));
    }

    @Test
    void determineFraudAction_block_onGeoAnomaly() {
        assertEquals(FraudAction.BLOCK,
                fraudSignalAnalyzer.determineFraudAction(0.99, java.util.List.of("GEO_ANOMALY_DETECTED")));
    }

    @Test
    void determineFraudAction_block_onNewDevice() {
        assertEquals(FraudAction.BLOCK,
                fraudSignalAnalyzer.determineFraudAction(0.99, java.util.List.of("NEW_DEVICE_PATTERN")));
    }
}

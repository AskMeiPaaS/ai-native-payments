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
        lenient().when(fraudConfig.getHighValueThreshold()).thenReturn(50000.0);
        lenient().when(fraudConfig.getBaselineScore()).thenReturn(0.02);
        lenient().when(fraudConfig.getBehavioralRiskWeight()).thenReturn(0.08);
        lenient().when(fraudConfig.getThresholdBlock()).thenReturn(0.70);
        lenient().when(fraudConfig.getThresholdEscalate()).thenReturn(0.50);
        lenient().when(fraudConfig.getThresholdMonitor()).thenReturn(0.30);
        lenient().when(fraudConfig.getHardblockSignals()).thenReturn(Set.of("GEO_ANOMALY_DETECTED", "NEW_DEVICE_PATTERN"));
        lenient().when(fraudConfig.getPenaltyForSignal("HIGH_VALUE_TRANSACTION")).thenReturn(0.02);
        lenient().when(fraudConfig.getPenaltyForSignal("GEO_ANOMALY_DETECTED")).thenReturn(0.10);
        lenient().when(fraudConfig.getPenaltyForSignal("NEW_DEVICE_PATTERN")).thenReturn(0.10);
        lenient().when(fraudConfig.getPenaltyForSignal("UNUSUAL_TIMING")).thenReturn(0.03);
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
    void computeRiskScore_withNoSignals_returns002Baseline() {
        double score = fraudSignalAnalyzer.computeRiskScore(0.0, java.util.List.of());
        assertEquals(0.02, score, 0.001);
    }

    @Test
    void computeRiskScore_withHighValueSignal_adds002Penalty() {
        double score = fraudSignalAnalyzer.computeRiskScore(0.0, java.util.List.of("HIGH_VALUE_TRANSACTION"));
        assertEquals(0.04, score, 0.001); // 0.02 + 0.02
    }

    @Test
    void computeRiskScore_withRealBehavioralScore_scalesBehavioralPenalty() {
        double score = fraudSignalAnalyzer.computeRiskScore(0.90, java.util.List.of());
        assertEquals(0.028, score, 0.001); // 0.02 + (1.0 - 0.90) * 0.08 = 0.02 + 0.008
    }

    // ── Action determination tests ──

    @Test
    void determineFraudAction_approve_whenRiskBelow030() {
        assertEquals(FraudAction.APPROVE, fraudSignalAnalyzer.determineFraudAction(0.20, java.util.List.of()));
    }

    @Test
    void determineFraudAction_monitor_whenRiskBetween030And050() {
        assertEquals(FraudAction.MONITOR, fraudSignalAnalyzer.determineFraudAction(0.35, java.util.List.of()));
    }

    @Test
    void determineFraudAction_escalate_whenRiskBetween050And070() {
        assertEquals(FraudAction.ESCALATE, fraudSignalAnalyzer.determineFraudAction(0.55, java.util.List.of()));
    }

    @Test
    void determineFraudAction_block_whenRiskAbove070() {
        assertEquals(FraudAction.BLOCK, fraudSignalAnalyzer.determineFraudAction(0.75, java.util.List.of()));
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

    // ── Signal analysis tests ──

    @Test
    void analyzeFraudSignals_highValueAmount_triggersSignal() {
        var signals = fraudSignalAnalyzer.analyzeFraudSignals("some context", "transfer 60000", 60000, "Bob", "UPI");
        assertTrue(signals.contains("HIGH_VALUE_TRANSACTION"));
    }

    @Test
    void analyzeFraudSignals_lowAmount_noHighValueSignal() {
        var signals = fraudSignalAnalyzer.analyzeFraudSignals("some context", "transfer 100", 100, "Bob", "UPI");
        assertFalse(signals.contains("HIGH_VALUE_TRANSACTION"));
    }

    @Test
    void analyzeFraudSignals_ragPolicyTextDoesNotTriggerGeoAnomaly() {
        // RAG docs mention "geo-anomaly" as policy text — must NOT trigger the signal
        String ragPolicyText = "Flag if: new device, unusual time-of-day, geo-anomaly";
        var signals = fraudSignalAnalyzer.analyzeFraudSignals(ragPolicyText, "transfer 500 to Ramesh", 500, "Ramesh", "UPI");
        assertFalse(signals.contains("GEO_ANOMALY_DETECTED"),
                "Policy text mentioning 'geo-anomaly' should not trigger GEO_ANOMALY_DETECTED signal");
    }

    @Test
    void analyzeFraudSignals_ragPolicyTextDoesNotTriggerNewDevice() {
        // RAG docs mention "new device" as policy text — must NOT trigger the signal
        String ragPolicyText = "New device: first-time device fingerprint with high-value transaction";
        var signals = fraudSignalAnalyzer.analyzeFraudSignals(ragPolicyText, "transfer 500 to Ramesh", 500, "Ramesh", "UPI");
        assertFalse(signals.contains("NEW_DEVICE_PATTERN"),
                "Policy text mentioning 'new device' should not trigger NEW_DEVICE_PATTERN signal");
    }

    @Test
    void analyzeFraudSignals_unusualTiming_triggersOnMidnightIntent() {
        String ragWithTimeOfDay = "time-of-day analysis recommended";
        var signals = fraudSignalAnalyzer.analyzeFraudSignals(ragWithTimeOfDay, "send money at midnight", 1000, "Bob", "UPI");
        assertTrue(signals.contains("UNUSUAL_TIMING"));
    }

    @Test
    void analyzeFraudSignals_normalIntent_noUnusualTiming() {
        String ragWithTimeOfDay = "time-of-day analysis recommended";
        var signals = fraudSignalAnalyzer.analyzeFraudSignals(ragWithTimeOfDay, "transfer 1000 to Bob", 1000, "Bob", "UPI");
        assertFalse(signals.contains("UNUSUAL_TIMING"));
    }
}

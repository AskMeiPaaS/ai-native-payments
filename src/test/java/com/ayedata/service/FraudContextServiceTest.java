package com.ayedata.service;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class FraudContextServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private ScoringModel scoringModel;

    @InjectMocks
    private FraudContextService fraudContextService;

    @BeforeEach
    void setUp() {
        // Setup mock embedding
        Embedding mockEmbedding = new Embedding(new float[]{0.1f, 0.2f, 0.3f});
        Response<Embedding> mockResponse = Response.from(mockEmbedding);
        lenient().when(embeddingModel.embed(anyString())).thenReturn(mockResponse);
    }

    @Test
    void getBehavioralSimilarityScore_withNullSessionId_returnsZero() {
        double result = fraudContextService.getBehavioralSimilarityScore(null, "validUserId");
        assertEquals(0.0, result);
    }

    @Test
    void getBehavioralSimilarityScore_withBlankSessionId_returnsZero() {
        double result = fraudContextService.getBehavioralSimilarityScore("", "validUserId");
        assertEquals(0.0, result);
    }

    @Test
    void getBehavioralSimilarityScore_withNullUserId_returnsZero() {
        double result = fraudContextService.getBehavioralSimilarityScore("validSession", null);
        assertEquals(0.0, result);
    }

    @Test
    void getBehavioralSimilarityScore_withBlankUserId_returnsZero() {
        double result = fraudContextService.getBehavioralSimilarityScore("validSession", "");
        assertEquals(0.0, result);
    }

    @Test
    void getBehavioralSimilarityScore_withInvalidUserIdFormat_returnsZero() {
        double result = fraudContextService.getBehavioralSimilarityScore("validSession", "invalid@user!id");
        assertEquals(0.0, result);
    }

    @Test
    void getBehavioralSimilarityScore_withTooShortUserId_returnsZero() {
        double result = fraudContextService.getBehavioralSimilarityScore("validSession", "123");
        assertEquals(0.0, result);
    }

    @Test
    void getBehavioralSimilarityScore_withTooLongUserId_returnsZero() {
        String longUserId = "a".repeat(65); // 65 characters, exceeds max 64
        double result = fraudContextService.getBehavioralSimilarityScore("validSession", longUserId);
        assertEquals(0.0, result);
    }

    @Test
    void getBehavioralSimilarityScore_withValidInputs_returnsZeroWhenNoResults() {
        // When no results are found, should return 0.0 (fail-safe)
        double result = fraudContextService.getBehavioralSimilarityScore("validSession", "validUserId123");
        assertEquals(0.0, result);
    }
}
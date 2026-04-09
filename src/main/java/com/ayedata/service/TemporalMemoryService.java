package com.ayedata.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.ayedata.util.TextUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Temporal Memory Service — persists every conversational turn with an embedding
 * and recalls semantically relevant past turns via MongoDB vector search.
 *
 * <ul>
 *   <li>Storage: {@code pass_memory.memory_timeline} — one document per turn</li>
 *   <li>Recall: Atlas $vectorSearch on {@code turnEmbedding}, filtered by {@code sessionId}</li>
 *   <li>Fallback: recency sort when Atlas is unavailable (local MongoDB)</li>
 * </ul>
 *
 * Archiving is intended to be called asynchronously on a virtual thread so it
 * never blocks the agent response path.
 */
@Service
public class TemporalMemoryService {

    private static final Logger log = LoggerFactory.getLogger(TemporalMemoryService.class);
    public static final String COLLECTION = "memory_timeline";

    private final EmbeddingModel embeddingModel;
    private final MongoTemplate memoryMongoTemplate;

    public TemporalMemoryService(EmbeddingModel embeddingModel,
                                 @Qualifier("memoryMongoTemplate") MongoTemplate memoryMongoTemplate) {
        this.embeddingModel = embeddingModel;
        this.memoryMongoTemplate = memoryMongoTemplate;
    }

    // -----------------------------------------------------------------------
    // Write path
    // -----------------------------------------------------------------------

    /**
     * Probe the Voyage AI embedding service with a tiny payload.
     * Returns {@code true} if the service responds with a valid vector, {@code false} otherwise.
     * Intended as a lightweight pre-flight check before orchestration — avoids calling the
     * LLM with degraded (no-RAG, no-recall) context when the embedding backend is down.
     */
    public boolean checkEmbeddingHealth() {
        try {
            float[] v = embeddingModel.embed(TextSegment.from("health")).content().vector();
            return v != null && v.length > 0;
        } catch (Exception e) {
            log.error("Embedding health check failed (Voyage AI unreachable): {}", e.getMessage());
            return false;
        }
    }

    /**
     * Archive a completed turn (user prompt + agent reply) with its embedding.
     * Call this on a virtual thread — it blocks on Voyage AI network I/O.
     *
     * @throws RuntimeException if the embedding call fails — callers should
     *         catch this to decide whether to proceed without archival.
     */
    public void archiveTurn(String sessionId, String userText, String aiText) {
        try {
            // Embed both sides of the turn as a single unit
            String combined = "User: " + userText + "\nAgent: " + aiText;
            float[] vector = embeddingModel.embed(TextSegment.from(combined)).content().vector();

            Document doc = new Document()
                    .append("sessionId", sessionId)
                    .append("userText", TextUtils.truncate(userText, 2000))
                    .append("aiText", TextUtils.truncate(aiText, 4000))
                    .append("turnEmbedding", TextUtils.toDoubleList(vector))
                    .append("timestamp", new Date());

            memoryMongoTemplate.insert(doc, COLLECTION);
            log.debug("Archived temporal turn for session {} ({} chars)", sessionId, combined.length());

        } catch (Exception e) {
            log.error("❌ Temporal archive FAILED for session {} — embedding service may be down: {}",
                    sessionId, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Read path
    // -----------------------------------------------------------------------

    /**
     * Recall the most semantically relevant past turns for the given query.
     * Returns a list of maps with keys {@code userText} and {@code aiText}.
     *
     * Tries Atlas $vectorSearch first; falls back to recency on failure.
     */
    public List<Map<String, String>> recallRelevantHistory(String sessionId, String query, int topK) {
        try {
            float[] queryVector = embeddingModel.embed(TextSegment.from(query)).content().vector();
            List<Document> results = vectorSearchTimeline(sessionId, queryVector, topK);
            if (!results.isEmpty()) {
                log.debug("Temporal recall: {} turns via vector search for session {}", results.size(), sessionId);
                return toTurnList(results);
            }
        } catch (Exception e) {
            log.debug("Vector recall unavailable, falling back to recency: {}", e.getMessage());
        }
        return recentHistory(sessionId, topK);
    }

    // -----------------------------------------------------------------------
    // Index setup (called by DatabaseInitializer)
    // -----------------------------------------------------------------------

    /**
     * Ensure a plain ascending index on {@code sessionId} + {@code timestamp}
     * for the recency-fallback query path. Safe to call multiple times.
     */
    public void ensureIndexes() {
        try {
            com.mongodb.client.MongoCollection<org.bson.Document> collection = 
                    memoryMongoTemplate.getCollection(COLLECTION);
            
            // Compound index for recency-fallback query path
            org.bson.Document indexKey = new org.bson.Document()
                    .append("sessionId", 1)
                    .append("timestamp", -1);
            
            com.mongodb.client.model.IndexOptions indexOptions = 
                    new com.mongodb.client.model.IndexOptions()
                            .name("sessionId_timestamp_idx");
            
            collection.createIndex(indexKey, indexOptions);

            // TTL index — auto-delete temporal turns after 7 days to prevent unbounded growth
            collection.createIndex(
                    new org.bson.Document("timestamp", 1),
                    new com.mongodb.client.model.IndexOptions()
                            .name("memory_timeline_ttl")
                            .expireAfter(7L, TimeUnit.DAYS));

            log.info("✅ memory_timeline indexes ensured (incl. 7-day TTL)");
        } catch (com.mongodb.MongoCommandException ex) {
            if (ex.getMessage().contains("already exists")) {
                log.debug("memory_timeline indexes already exist");
            } else {
                log.warn("memory_timeline index creation issue: {}", ex.getMessage());
            }
        } catch (Exception e) {
            log.warn("memory_timeline index creation skipped: {}", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private List<Document> vectorSearchTimeline(String sessionId, float[] queryVector, int topK) {
        Document vectorSearchStage = new Document("$vectorSearch", new Document()
                .append("index", "memory_timeline_vector_index")
                .append("path", "turnEmbedding")
                .append("queryVector", TextUtils.toDoubleList(queryVector))
                .append("numCandidates", topK * 15)
                .append("limit", topK)
                .append("filter", new Document("sessionId", sessionId)));

        Document projectStage = new Document("$project", new Document()
                .append("userText", 1)
                .append("aiText", 1)
                .append("timestamp", 1)
                .append("score", new Document("$meta", "vectorSearchScore")));

        try (com.mongodb.client.MongoCursor<Document> cursor = memoryMongoTemplate.getDb()
                .getCollection(COLLECTION)
                .aggregate(List.of(vectorSearchStage, projectStage), Document.class)
                .cursor()) {
            List<Document> results = new ArrayList<>();
            cursor.forEachRemaining(results::add);
            return results;
        }
    }

    private List<Map<String, String>> recentHistory(String sessionId, int topK) {
        Query query = Query.query(Criteria.where("sessionId").is(sessionId));
        query.with(Sort.by(Sort.Direction.DESC, "timestamp"));
        query.limit(topK);
        return toTurnList(memoryMongoTemplate.find(query, Document.class, COLLECTION));
    }

    private static List<Map<String, String>> toTurnList(List<Document> docs) {
        return docs.stream()
                .map(d -> Map.of(
                        "userText", TextUtils.nullToEmpty(d.getString("userText")),
                        "aiText", TextUtils.nullToEmpty(d.getString("aiText"))))
                .collect(Collectors.toList());
    }


}

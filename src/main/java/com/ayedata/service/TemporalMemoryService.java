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

import java.util.*;
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
     * Archive a completed turn (user prompt + agent reply) with its embedding.
     * Call this on a virtual thread — it blocks on Voyage AI network I/O.
     */
    public void archiveTurn(String sessionId, String userText, String aiText) {
        try {
            // Embed both sides of the turn as a single unit
            String combined = "User: " + userText + "\nAgent: " + aiText;
            float[] vector = embeddingModel.embed(TextSegment.from(combined)).content().vector();

            Document doc = new Document()
                    .append("sessionId", sessionId)
                    .append("userText", truncate(userText, 2000))
                    .append("aiText", truncate(aiText, 4000))
                    .append("turnEmbedding", toDoubleList(vector))
                    .append("timestamp", new Date());

            memoryMongoTemplate.insert(doc, COLLECTION);
            log.debug("Archived temporal turn for session {} ({} chars)", sessionId, combined.length());

        } catch (Exception e) {
            log.warn("Temporal archive skipped for session {}: {}", sessionId, e.getMessage());
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
            // Use MongoDB's native API to create indexes, avoiding deprecated IndexOperations.ensureIndex()
            com.mongodb.client.MongoCollection<org.bson.Document> collection = 
                    memoryMongoTemplate.getCollection(COLLECTION);
            
            org.bson.Document indexKey = new org.bson.Document()
                    .append("sessionId", 1)
                    .append("timestamp", -1);
            
            com.mongodb.client.model.IndexOptions indexOptions = 
                    new com.mongodb.client.model.IndexOptions()
                            .name("sessionId_timestamp_idx");
            
            collection.createIndex(indexKey, indexOptions);
            log.info("✅ memory_timeline indexes ensured");
        } catch (com.mongodb.MongoCommandException ex) {
            // Index might already exist - this is fine
            if (ex.getMessage().contains("already exists")) {
                log.debug("Index sessionId_timestamp_idx already exists");
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
        // $vectorSearch with sessionId filter — requires Atlas vector index with
        // the filter field configured (see DatabaseInitializer).
        Document vectorSearchStage = new Document("$vectorSearch", new Document()
                .append("index", "memory_timeline_vector_index")
                .append("path", "turnEmbedding")
                .append("queryVector", toDoubleList(queryVector))
                .append("numCandidates", topK * 15)
                .append("limit", topK)
                .append("filter", new Document("sessionId", sessionId)));

        Document projectStage = new Document("$project", new Document()
                .append("userText", 1)
                .append("aiText", 1)
                .append("timestamp", 1)
                .append("score", new Document("$meta", "vectorSearchScore")));

        return memoryMongoTemplate.getDb()
                .getCollection(COLLECTION)
                .aggregate(List.of(vectorSearchStage, projectStage), Document.class)
                .into(new ArrayList<>());
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
                        "userText", nullToEmpty(d.getString("userText")),
                        "aiText", nullToEmpty(d.getString("aiText"))))
                .collect(Collectors.toList());
    }

    private static List<Double> toDoubleList(float[] floats) {
        List<Double> list = new ArrayList<>(floats.length);
        for (float f : floats) list.add((double) f);
        return list;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

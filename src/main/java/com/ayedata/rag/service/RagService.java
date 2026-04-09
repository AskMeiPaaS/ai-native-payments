package com.ayedata.rag.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.ayedata.util.TextUtils;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Retrieval-Augmented Generation (RAG) service backed by MongoDB.
 *
 * <ul>
 *   <li>Storage: {@code pass_memory.rag_knowledge} — payment-domain knowledge documents</li>
 *   <li>Retrieval: Atlas $vectorSearch on {@code embedding}, followed by Voyage AI reranking</li>
 *   <li>Ingestion: {@link #ingestDocument} embeds and upserts a document (idempotent by id)</li>
 * </ul>
 *
 * When Atlas is unavailable (local MongoDB) the vector search step is silently
 * skipped and an empty context is returned — the agent still works but without
 * RAG enrichment.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    public static final String COLLECTION = "rag_knowledge";

    private final EmbeddingModel embeddingModel;
    private final ScoringModel scoringModel;
    private final MongoTemplate memoryMongoTemplate;

    public RagService(EmbeddingModel embeddingModel,
                      ScoringModel scoringModel,
                      @Qualifier("memoryMongoTemplate") MongoTemplate memoryMongoTemplate) {
        this.embeddingModel = embeddingModel;
        this.scoringModel = scoringModel;
        this.memoryMongoTemplate = memoryMongoTemplate;
    }

    // -----------------------------------------------------------------------
    // Ingestion
    // -----------------------------------------------------------------------

    /**
     * Embed and upsert a knowledge document. Safe to call repeatedly — idempotent by {@code id}.
     *
     * @param id      Stable identifier (e.g. "upi-mandate-overview")
     * @param title   Short human-readable title
     * @param content Full document text that will be embedded and served as RAG context
     */
    public void ingestDocument(String id, String title, String content) {
        try {
            float[] vector = embeddingModel.embed(TextSegment.from(content)).content().vector();

            Query query = Query.query(Criteria.where("_id").is(id));
            Update update = Update.update("title", title)
                    .set("content", content)
                    .set("embedding", TextUtils.toDoubleList(vector))
                    .set("updatedAt", new Date());

            memoryMongoTemplate.upsert(query, update, COLLECTION);
            log.info("Ingested RAG document '{}' (id={})", title, id);
        } catch (Exception e) {
            log.warn("Failed to ingest RAG document '{}': {}", id, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Retrieval
    // -----------------------------------------------------------------------

    /**
     * Retrieve and rerank the most relevant knowledge chunks for a query.
     *
     * <ol>
     *   <li>Embed the query via Voyage AI</li>
     *   <li>Run $vectorSearch on {@code rag_knowledge.embedding} (Atlas only)</li>
     *   <li>Rerank with Voyage AI reranker</li>
     *   <li>Return the concatenated top-k chunk texts</li>
     * </ol>
     *
     * Returns an empty string if Atlas is unavailable or no documents match.
     */
    private static final int MAX_CHUNK_CHARS = 700;
    private static final int MAX_RAG_CONTEXT_CHARS = 1500;

    public String retrieveContext(String query, int topK) {
        try {
            float[] queryVector = embeddingModel.embed(TextSegment.from(query)).content().vector();
            List<Document> candidates = vectorSearchKnowledge(queryVector, topK * 2);

            if (candidates.isEmpty()) {
                log.debug("RAG: no candidates for query '{}'", TextUtils.truncateWithEllipsis(query, 80));
                return "";
            }

            // Rerank
            List<TextSegment> segments = candidates.stream()
                    .map(d -> TextSegment.from(TextUtils.nullToEmpty(d.getString("content"))))
                    .collect(Collectors.toList());

            List<Double> scores = scoringModel.scoreAll(segments, query).content();

            // Per-chunk budget = total budget / topK
            int perChunkBudget = Math.min(MAX_CHUNK_CHARS, MAX_RAG_CONTEXT_CHARS / topK);

            List<String> topChunks = IntStream.range(0, candidates.size())
                    .boxed()
                    .sorted((i, j) -> Double.compare(scores.get(j), scores.get(i)))
                    .limit(topK)
                    .filter(i -> scores.get(i) > 0.0)
                    .map(i -> TextUtils.truncateWithEllipsis(TextUtils.nullToEmpty(candidates.get(i).getString("content")), perChunkBudget))
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());

            String result = String.join("\n---\n", topChunks);

            // Final enforcement of total RAG budget
            if (result.length() > MAX_RAG_CONTEXT_CHARS) {
                result = TextUtils.truncateWithEllipsis(result, MAX_RAG_CONTEXT_CHARS);
            }

            log.info("RAG: {} chunk(s), {} chars (budget={}/chunk)", topChunks.size(), result.length(), perChunkBudget);
            return result;

        } catch (Exception e) {
            log.debug("RAG retrieval skipped (degraded mode): {}", e.getMessage());
            return "";
        }
    }

    /**
     * True if the {@code rag_knowledge} collection contains at least one document.
     */
    public boolean hasDocuments() {
        try {
            return memoryMongoTemplate.count(new Query(), COLLECTION) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Return the number of documents in {@code rag_knowledge}.
     */
    public long documentCount() {
        try {
            return memoryMongoTemplate.count(new Query(), COLLECTION);
        } catch (Exception e) {
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // Index setup (called by DatabaseInitializer)
    // -----------------------------------------------------------------------

    /**
     * Ensure a plain text index on {@code title} for admin inspection tooling.
     */
    public void ensureIndexes() {
        try {
            memoryMongoTemplate.indexOps(COLLECTION)
                    .createIndex(new Index()
                            .on("title", Sort.Direction.ASC));
            log.info("✅ rag_knowledge indexes ensured");
        } catch (Exception e) {
            log.warn("rag_knowledge index creation skipped: {}", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private List<Document> vectorSearchKnowledge(float[] queryVector, int limit) {
        Document vectorSearchStage = new Document("$vectorSearch", new Document()
                .append("index", "rag_knowledge_vector_index")
                .append("path", "embedding")
                .append("queryVector", TextUtils.toDoubleList(queryVector))
                .append("numCandidates", limit * 10)
                .append("limit", limit));

        Document projectStage = new Document("$project", new Document()
                .append("title", 1)
                .append("content", 1)
                .append("score", new Document("$meta", "vectorSearchScore")));

        try {
            return memoryMongoTemplate.getDb()
                    .getCollection(COLLECTION)
                    .aggregate(List.of(vectorSearchStage, projectStage), Document.class)
                    .into(new ArrayList<>());
        } catch (Exception e) {
            log.debug("$vectorSearch unavailable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

}

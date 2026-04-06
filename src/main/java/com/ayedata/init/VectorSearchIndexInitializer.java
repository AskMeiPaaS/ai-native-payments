package com.ayedata.init;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Creates and maintains Atlas Vector Search indexes for all databases.
 * Includes behavioral fingerprint (primary), temporal memory, and RAG knowledge indexes.
 */
@Component
public class VectorSearchIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchIndexInitializer.class);

    private final MongoTemplate primaryTemplate;
    private final MongoTemplate memoryTemplate;

    @Value("${EMBEDDING_DIMENSIONS:1024}")
    private int embeddingDimensions;

    public VectorSearchIndexInitializer(
            @Qualifier("primaryMongoTemplate") MongoTemplate primaryTemplate,
            @Qualifier("memoryMongoTemplate") MongoTemplate memoryTemplate) {
        this.primaryTemplate = primaryTemplate;
        this.memoryTemplate = memoryTemplate;
    }

    /**
     * Create behavioral fingerprint vector search index on the primary database.
     */
    public void createBehavioralVectorIndex() {
        try {
            log.info("⚙️ Verifying Atlas Vector Search Index...");

            String commandJson = """
                {
                  "createSearchIndexes": "user_profiles",
                  "indexes": [
                    {
                      "name": "behavioral_vector_index",
                      "type": "vectorSearch",
                      "definition": {
                        "fields": [
                          {
                            "type": "vector",
                            "numDimensions": %d,
                            "path": "behavioralFingerprint.baselineVector",
                            "similarity": "cosine"
                          }
                        ]
                      }
                    }
                  ]
                }
            """.formatted(embeddingDimensions);

            primaryTemplate.executeCommand(Document.parse(commandJson));
            log.info("✅ Vector Search Index 'behavioral_vector_index' verified/creation initiated.");
        } catch (Exception e) {
            log.warn("⚠️ Vector Search index creation skipped or failed. (Note: Requires MongoDB Atlas): {}", e.getMessage());
        }
    }

    /**
     * Create Atlas vector search indexes for memory_timeline and rag_knowledge.
     * Safe to call on every startup — Atlas silently ignores duplicate index creation.
     */
    public void createMemoryVectorIndexes() {
        String timelineIndexJson = """
            {
              "createSearchIndexes": "memory_timeline",
              "indexes": [
                {
                  "name": "memory_timeline_vector_index",
                  "type": "vectorSearch",
                  "definition": {
                    "fields": [
                      {
                        "type": "vector",
                        "numDimensions": %d,
                        "path": "turnEmbedding",
                        "similarity": "cosine"
                      },
                      {
                        "type": "filter",
                        "path": "sessionId"
                      }
                    ]
                  }
                }
              ]
            }
        """.formatted(embeddingDimensions);

        String ragIndexJson = """
            {
              "createSearchIndexes": "rag_knowledge",
              "indexes": [
                {
                  "name": "rag_knowledge_vector_index",
                  "type": "vectorSearch",
                  "definition": {
                    "fields": [
                      {
                        "type": "vector",
                        "numDimensions": %d,
                        "path": "embedding",
                        "similarity": "cosine"
                      }
                    ]
                  }
                }
              ]
            }
        """.formatted(embeddingDimensions);

        for (String json : List.of(timelineIndexJson, ragIndexJson)) {
            try {
                memoryTemplate.executeCommand(Document.parse(json));
                log.info("✅ Memory Atlas vector index initiated.");
            } catch (Exception e) {
                log.warn("⚠️ Memory vector index skipped (requires Atlas): {}", e.getMessage());
            }
        }
    }
}

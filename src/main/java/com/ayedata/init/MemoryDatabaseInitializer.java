package com.ayedata.init;

import com.ayedata.rag.service.RagService;
import com.ayedata.service.TemporalMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Initializes the Memory database — creates collections and indexes
 * for temporal memory (memory_timeline), RAG (rag_knowledge), and
 * chat memory (agent_chat_memory).
 */
@Component
public class MemoryDatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(MemoryDatabaseInitializer.class);

    private final MongoTemplate memoryTemplate;
    private final DatabaseConnectionValidator connectionValidator;
    private final VectorSearchIndexInitializer vectorSearchIndexInitializer;
    private final TemporalMemoryService temporalMemoryService;
    private final RagService ragService;

    public MemoryDatabaseInitializer(
            @Qualifier("memoryMongoTemplate") MongoTemplate memoryTemplate,
            DatabaseConnectionValidator connectionValidator,
            VectorSearchIndexInitializer vectorSearchIndexInitializer,
            TemporalMemoryService temporalMemoryService,
            RagService ragService) {
        this.memoryTemplate = memoryTemplate;
        this.connectionValidator = connectionValidator;
        this.vectorSearchIndexInitializer = vectorSearchIndexInitializer;
        this.temporalMemoryService = temporalMemoryService;
        this.ragService = ragService;
    }

    public void initialize() {
        try {
            Set<String> existing = connectionValidator.waitForDatabase(memoryTemplate, "Memory");
            for (String col : List.of(TemporalMemoryService.COLLECTION, RagService.COLLECTION, "agent_chat_memory")) {
                if (!existing.contains(col)) {
                    memoryTemplate.createCollection(col);
                    log.info("✅ Created memory collection: {}", col);
                }
            }
            temporalMemoryService.ensureIndexes();
            ragService.ensureIndexes();
            vectorSearchIndexInitializer.createMemoryVectorIndexes();
        } catch (Exception e) {
            log.warn("⚠️ Memory database init failed (non-fatal): {}", e.getMessage());
        }
    }
}

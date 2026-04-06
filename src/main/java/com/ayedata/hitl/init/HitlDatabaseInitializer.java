package com.ayedata.hitl.init;

import com.ayedata.init.DatabaseConnectionValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Initializes the HITL (Human-In-The-Loop) database — creates collections
 * and indexes for the escalation workflow.
 */
@Component
public class HitlDatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(HitlDatabaseInitializer.class);
    private static final String HITL_COLLECTION = "hitl_escalations";

    private final MongoTemplate hitlTemplate;
    private final DatabaseConnectionValidator connectionValidator;

    public HitlDatabaseInitializer(
            @Qualifier("hitlMongoTemplate") MongoTemplate hitlTemplate,
            DatabaseConnectionValidator connectionValidator) {
        this.hitlTemplate = hitlTemplate;
        this.connectionValidator = connectionValidator;
    }

    public void initialize() {
        Set<String> existing = connectionValidator.waitForDatabase(hitlTemplate, "HITL");

        if (!existing.contains(HITL_COLLECTION)) {
            hitlTemplate.createCollection(HITL_COLLECTION);
            log.info("✅ Created missing HITL collection: {}", HITL_COLLECTION);
        }

        IndexOperations indexOps = hitlTemplate.indexOps(HITL_COLLECTION);

        indexOps.createIndex(new Index().on("escalationId", Sort.Direction.ASC));
        log.info("✅ Created index on 'escalationId' for HITL collection");

        indexOps.createIndex(new Index()
                .on("status", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC));
        log.info("✅ Created compound index on 'status, createdAt' for HITL collection");

        indexOps.createIndex(new Index().on("sessionId", Sort.Direction.ASC));
        log.info("✅ Created index on 'sessionId' for HITL collection");

        indexOps.createIndex(new Index().on("transactionId", Sort.Direction.ASC));
        log.info("✅ Created index on 'transactionId' for HITL collection");

        indexOps.createIndex(new Index()
                .on("operatorId", Sort.Direction.ASC)
                .on("resolvedAt", Sort.Direction.DESC));
        log.info("✅ Created compound index on 'operatorId, resolvedAt' for HITL collection");
    }
}

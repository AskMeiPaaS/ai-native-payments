package com.ayedata.audit.init;

import com.ayedata.audit.domain.AuditRecord;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Ensures the audit collection is indexed for session replay, product analysis,
 * and long-term retrieval by session, event type, and trace ID.
 * 
 * Uses createIndex() instead of the deprecated ensureIndex() method.
 */
@Component
public class AuditIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(AuditIndexInitializer.class);

    private final MongoTemplate auditMongoTemplate;

    public AuditIndexInitializer(@Qualifier("auditMongoTemplate") MongoTemplate auditMongoTemplate) {
        this.auditMongoTemplate = auditMongoTemplate;
    }

    @PostConstruct
    public void ensureIndexes() {
        var indexOps = auditMongoTemplate.indexOps(AuditRecord.class);
        
        indexOps.createIndex(new Index().on("sessionId", Sort.Direction.ASC).on("timestamp", Sort.Direction.DESC));
        log.info("✅ Created index on 'sessionId, timestamp' for audit collection");
        
        indexOps.createIndex(new Index().on("eventType", Sort.Direction.ASC).on("timestamp", Sort.Direction.DESC));
        log.info("✅ Created index on 'eventType, timestamp' for audit collection");
        
        indexOps.createIndex(new Index().on("traceId", Sort.Direction.ASC));
        log.info("✅ Created index on 'traceId' for audit collection");
    }
}
package com.ayedata.config;

/**
 * MongoDB Connection Configuration (DEPRECATED)
 * 
 * All MongoDB client creation has been moved to MongoConfig.java
 * This class is kept for backwards compatibility but is no longer used.
 * 
 * MongoConfig handles:
 * - Primary MongoClient (marked @Primary for Spring Data)
 * - Audit MongoClient
 * - HITL MongoClient
 * 
 * The old Queryable Encryption setup has been removed as we now use
 * Voyage AI APIs for embeddings instead of local encryption.
 */
public class EncryptionConfig {
    // All MongoDB connection setup moved to MongoConfig.java
}

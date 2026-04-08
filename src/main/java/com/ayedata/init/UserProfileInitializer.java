package com.ayedata.init;

import com.ayedata.domain.UserProfile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds a fixed set of demo users into the {@code user_profiles} collection on startup.
 *
 * <p>Each user's PII fields are persisted through the primary auto-encrypted MongoClient,
 * so MongoDB Queryable Encryption is applied transparently per configured schema.
 *
 * <p>This initializer is idempotent: it only creates users that do not already exist.
 */
@Component
public class UserProfileInitializer {

    private static final Logger log = LoggerFactory.getLogger(UserProfileInitializer.class);

    private static final String DEMO_USERS_RESOURCE = "seeds/demo-users.json";
    private static volatile List<String> demoUserIds = List.of();
    private static volatile Map<String, String> demoUserRegistry = Map.of();
    private static volatile Map<String, String> demoAccountRegistry = Map.of();

    private final MongoTemplate primaryTemplate;
    private final List<DemoUser> demoUsers;

    public UserProfileInitializer(
            @Qualifier("primaryMongoTemplate") MongoTemplate primaryTemplate) {
        this.primaryTemplate = primaryTemplate;
        this.demoUsers = loadDemoUsers(new ObjectMapper());
        demoUserIds = this.demoUsers.stream().map(DemoUser::userId).toList();
        Map<String, String> registry = new LinkedHashMap<>();
        Map<String, String> accounts = new LinkedHashMap<>();
        for (DemoUser u : this.demoUsers) {
            registry.put(u.userId(), u.displayName());
            if (u.accountNumber() != null) {
                accounts.put(u.accountNumber(), u.userId());
            }
        }
        demoUserRegistry = Collections.unmodifiableMap(registry);
        demoAccountRegistry = Collections.unmodifiableMap(accounts);
    }

    /**
     * Seeds all demo users. Safe to call multiple times (idempotent).
     */
    public void seedDemoUsers() {
        log.info("👤 Seeding demo user profiles...");
        int created = 0;
        for (DemoUser demo : demoUsers) {
            if (primaryTemplate.findById(demo.userId(), UserProfile.class) != null) {
                log.debug("  ↳ User {} already exists, skipping.", demo.userId());
                continue;
            }
            UserProfile profile = buildProfile(demo);
            primaryTemplate.save(profile);
            log.info("  ✅ Created demo user: {} ({})", demo.userId(), demo.displayName());
            created++;
        }
        if (created == 0) {
            log.info("✅ All demo users already present — nothing to seed.");
        } else {
            log.info("✅ Seeded {} new demo user(s).", created);
        }
    }

    /**
     * Returns the list of demo user IDs available for login.
     */
    public static List<String> getDemoUserIds() {
        return demoUserIds;
    }

    /**
     * Returns userId→displayName map of all registered demo users.
     */
    public static Map<String, String> getDemoUserRegistry() {
        return demoUserRegistry;
    }

    /**
     * Returns accountNumber→userId map of all registered demo users.
     */
    public static Map<String, String> getDemoAccountRegistry() {
        return demoAccountRegistry;
    }

    /**
     * Resolves a free-text beneficiary (userId, full name, first name, or account number) to a registered userId.
     * Returns null if no match is found.
     */
    public static String resolveUserIdByNameOrId(String input) {
        if (input == null || input.isBlank()) return null;
        String trimmed = input.trim();

        // Exact userId match (case-insensitive)
        for (var entry : demoUserRegistry.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(trimmed)) return entry.getKey();
        }
        // Exact account number match
        String byAccount = demoAccountRegistry.get(trimmed);
        if (byAccount != null) return byAccount;

        // Exact displayName match (case-insensitive)
        for (var entry : demoUserRegistry.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(trimmed)) return entry.getKey();
        }
        // Partial match: beneficiary is a substring of displayName (e.g. "Arjun" matches "Arjun Kumar")
        String lower = trimmed.toLowerCase();
        for (var entry : demoUserRegistry.entrySet()) {
            String dispLower = entry.getValue().toLowerCase();
            if (dispLower.contains(lower)) return entry.getKey();
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UserProfile buildProfile(DemoUser demo) {
        UserProfile profile = new UserProfile();
        profile.setId(demo.userId());
        profile.setDisplayName(demo.displayName());
        // Auto encryption is applied by the MongoDB driver based on QE schema map.
        profile.setEmail(demo.email());
        profile.setPhone(demo.phone());
        profile.setBank_account(demo.accountNumber());
        profile.setCurrentBalance(demo.openingBalance());
        profile.setCurrency("INR");
        profile.setLastUpdatedAt(Instant.now());
        profile.setBehavioralFingerprint(
                new UserProfile.BehavioralFingerprint(
                        List.of(0.98, 0.97, 0.96),
                        List.of("trusted-mobile")));
        return profile;
    }

    private List<DemoUser> loadDemoUsers(ObjectMapper objectMapper) {
        try {
            ClassPathResource resource = new ClassPathResource(DEMO_USERS_RESOURCE);
            List<DemoUser> loaded = objectMapper.readValue(resource.getInputStream(), new TypeReference<>() {});
            if (loaded.isEmpty()) {
                throw new IllegalStateException("Demo user list is empty");
            }
            log.info("Loaded {} demo users from {}", loaded.size(), DEMO_USERS_RESOURCE);
            return loaded;
        } catch (IOException e) {
            log.error("Failed to load demo users from {}", DEMO_USERS_RESOURCE, e);
            return Collections.emptyList();
        }
    }

    // -------------------------------------------------------------------------
    // Value record
    // -------------------------------------------------------------------------

    private record DemoUser(
            String userId,
            String displayName,
            String email,
            String phone,
            String accountNumber,
            double openingBalance) {}
}

package com.ayedata.init;

import com.ayedata.domain.UserProfile;
import com.ayedata.service.UserProfileEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Seeds a fixed set of demo users into the {@code user_profiles} collection on startup.
 *
 * <p>Each user's {@code email} and {@code phone} fields are encrypted with AES-256-GCM
 * via {@link UserProfileEncryptionService} before being persisted, simulating MongoDB
 * Queryable Encryption for PII data.
 *
 * <p>This initializer is idempotent: it only creates users that do not already exist.
 */
@Component
public class UserProfileInitializer {

    private static final Logger log = LoggerFactory.getLogger(UserProfileInitializer.class);

    /** Fixed demo user definitions (userId, displayName, email, phone, openingBalance). */
    private static final List<DemoUser> DEMO_USERS = List.of(
            new DemoUser("user001", "Arjun Kumar",  "arjun.kumar@example.com",  "+91-98765-43210", 50_000.00),
            new DemoUser("user002", "Priya Sharma", "priya.sharma@example.com", "+91-87654-32109", 75_000.00),
            new DemoUser("user003", "Rahul Patel",  "rahul.patel@example.com",  "+91-76543-21098", 30_000.00),
            new DemoUser("user004", "Kavya Singh",  "kavya.singh@example.com",  "+91-65432-10987", 100_000.00),
            new DemoUser("user005", "Amit Verma",   "amit.verma@example.com",   "+91-54321-09876", 25_000.00)
    );

    private final MongoTemplate primaryTemplate;
    private final UserProfileEncryptionService encryptionService;

    public UserProfileInitializer(
            @Qualifier("primaryMongoTemplate") MongoTemplate primaryTemplate,
            UserProfileEncryptionService encryptionService) {
        this.primaryTemplate = primaryTemplate;
        this.encryptionService = encryptionService;
    }

    /**
     * Seeds all demo users. Safe to call multiple times (idempotent).
     */
    public void seedDemoUsers() {
        log.info("👤 Seeding demo user profiles...");
        int created = 0;
        for (DemoUser demo : DEMO_USERS) {
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
        return DEMO_USERS.stream().map(DemoUser::userId).toList();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UserProfile buildProfile(DemoUser demo) {
        UserProfile profile = new UserProfile();
        profile.setId(demo.userId());
        profile.setDisplayName(demo.displayName());
        // Encrypt PII fields before persisting
        profile.setEmail(encryptionService.encrypt(demo.email()));
        profile.setPhone(encryptionService.encrypt(demo.phone()));
        profile.setCurrentBalance(demo.openingBalance());
        profile.setCurrency("INR");
        profile.setLastUpdatedAt(Instant.now());
        profile.setBehavioralFingerprint(
                new UserProfile.BehavioralFingerprint(
                        List.of(0.98, 0.97, 0.96),
                        List.of("trusted-mobile")));
        return profile;
    }

    // -------------------------------------------------------------------------
    // Value record
    // -------------------------------------------------------------------------

    private record DemoUser(
            String userId,
            String displayName,
            String email,
            String phone,
            double openingBalance) {}
}

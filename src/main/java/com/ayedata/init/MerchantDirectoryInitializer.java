package com.ayedata.init;

import com.ayedata.domain.MerchantProfile;
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
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Seeds the {@code merchant_directory} collection with demo merchants on startup.
 * Idempotent: only creates merchants that do not already exist.
 */
@Component
public class MerchantDirectoryInitializer {

    private static final Logger log = LoggerFactory.getLogger(MerchantDirectoryInitializer.class);
    private static final String SEED_RESOURCE = "seeds/demo-merchants.json";

    private static volatile Map<String, String> merchantRegistry = Map.of();

    private final MongoTemplate primaryTemplate;
    private final List<DemoMerchant> demoMerchants;

    public MerchantDirectoryInitializer(
            @Qualifier("primaryMongoTemplate") MongoTemplate primaryTemplate) {
        this.primaryTemplate = primaryTemplate;
        this.demoMerchants = loadDemoMerchants(new ObjectMapper());
        Map<String, String> registry = new LinkedHashMap<>();
        for (DemoMerchant m : demoMerchants) {
            registry.put(m.merchantId(), m.name());
        }
        merchantRegistry = Collections.unmodifiableMap(registry);
    }

    /**
     * Seed all demo merchants. Safe to call multiple times.
     */
    public void seedDemoMerchants() {
        log.info("🏪 Seeding merchant directory...");
        int created = 0;
        for (DemoMerchant demo : demoMerchants) {
            if (primaryTemplate.findById(demo.merchantId(), MerchantProfile.class) != null) {
                log.debug("  ↳ Merchant {} already exists, skipping.", demo.merchantId());
                continue;
            }
            MerchantProfile profile = buildProfile(demo);
            primaryTemplate.save(profile);
            log.info("  ✅ Created merchant: {} ({})", demo.merchantId(), demo.name());
            created++;
        }
        if (created == 0) {
            log.info("✅ All demo merchants already present — nothing to seed.");
        } else {
            log.info("✅ Seeded {} new merchant(s).", created);
        }
    }

    /**
     * Returns merchantId→name map of all registered demo merchants.
     */
    public static Map<String, String> getMerchantRegistry() {
        return merchantRegistry;
    }

    private MerchantProfile buildProfile(DemoMerchant demo) {
        MerchantProfile profile = new MerchantProfile();
        profile.setMerchantId(demo.merchantId());
        profile.setName(demo.name());
        profile.setCategory(demo.category());
        profile.setMccCode(demo.mccCode());
        profile.setUpiVpa(demo.upiVpa());
        profile.setAccountNumber(demo.accountNumber());
        profile.setMaxTransactionLimit(demo.maxTransactionLimit());
        profile.setSupportedChannels(demo.supportedChannels());
        profile.setCity(demo.city());
        profile.setState(demo.state());
        profile.setVerified(demo.verified());
        profile.setCreatedAt(Instant.now());
        return profile;
    }

    private List<DemoMerchant> loadDemoMerchants(ObjectMapper objectMapper) {
        try {
            ClassPathResource resource = new ClassPathResource(SEED_RESOURCE);
            List<DemoMerchant> loaded = objectMapper.readValue(resource.getInputStream(), new TypeReference<>() {});
            if (loaded.isEmpty()) {
                throw new IllegalStateException("Demo merchant list is empty");
            }
            log.info("Loaded {} demo merchants from {}", loaded.size(), SEED_RESOURCE);
            return loaded;
        } catch (IOException e) {
            log.error("Failed to load demo merchants from {}", SEED_RESOURCE, e);
            return Collections.emptyList();
        }
    }

    private record DemoMerchant(
            String merchantId,
            String name,
            String category,
            String mccCode,
            String upiVpa,
            String accountNumber,
            double maxTransactionLimit,
            List<String> supportedChannels,
            String city,
            String state,
            boolean verified) {}
}

package org.texboobcat.tessellate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    @AfterEach
    void restoreDefaults() {
        Config.reset();
    }

    @Test
    void resetRestoresDefaults() {
        Config.regionsEnabled = false;
        Config.sectionShift = 8;
        Config.workerThreads = 64;

        Config.reset();

        assertTrue(Config.regionsEnabled);
        assertEquals(2, Config.sectionShift);
        assertEquals(2, Config.mergeRadius);
        assertEquals(200, Config.verifyIntervalTicks);
        assertTrue(Config.scopedEntityTicking);
        assertTrue(Config.scopedBlockEntityTicking);
        assertTrue(Config.scopedScheduledTicks);
        assertTrue(Config.scopedBlockEvents);
        assertTrue(Config.adaptiveThrottling);
        assertEquals(25.0, Config.regionBudgetMillis);
        assertEquals(45.0, Config.targetTickMillis);
        assertEquals(2.0, Config.minThrottleMillis);
        assertEquals(16, Config.maxTickDivisor);
        assertTrue(Config.shardEntityStorage);
        assertFalse(Config.assertShardOwnership);
        assertTrue(Config.parallelTicking);
        assertTrue(Config.directWorkerChunkReads);
        assertTrue(Config.parallelNaturalSpawning);
        assertTrue(Config.asyncRegionLoops);
        assertEquals(0, Config.workerThreads);
        assertTrue(Config.threadLocalRandom);
        assertFalse(Config.serializeEntityTicks);
        assertEquals(Config.DEFAULT_MAIN_THREAD_ENTITIES, Config.mainThreadEntities);
        assertFalse(Config.diagnoseEntitySectionRaces);
        assertFalse(Config.strictGuard);
        assertFalse(Config.logRegionChanges);
        assertEquals(java.util.List.of(), Config.forceSerialMods);
        assertEquals(Config.COMPATIBILITY_REPORT_FUNCTION_ENDPOINT,
            Config.compatibilityReportEndpoint);
        assertEquals(Config.DEFAULT_COMPATIBILITY_RULES_API_KEY,
            Config.compatibilityReportApiKey);
        assertEquals(Config.DEFAULT_COMPATIBILITY_RULES_ENDPOINT,
            Config.compatibilityRulesEndpoint);
        assertEquals(Config.DEFAULT_COMPATIBILITY_RULES_API_KEY,
            Config.compatibilityRulesApiKey);
    }

    @Test
    void parallelTickingRequiresStorageAndThreadLocalRandom() {
        assertTrue(Config.parallelTickingConfigured());

        Config.parallelTicking = false;
        assertFalse(Config.parallelTickingConfigured());
        Config.parallelTicking = true;

        Config.shardEntityStorage = false;
        assertFalse(Config.parallelTickingConfigured());
        Config.shardEntityStorage = true;

        Config.threadLocalRandom = false;
        assertFalse(Config.parallelTickingConfigured());
    }

    @Test
    void compatibilityConfigRejectsInvalidModIds() {
        Config.configureCompatibility(java.util.List.of("c2me", "not-an-id", "c2me"),
            " https://example.invalid/report ", " public-key ");

        assertEquals(java.util.List.of("c2me"), Config.forceSerialMods);
        assertEquals("https://example.invalid/report", Config.compatibilityReportEndpoint);
        assertEquals("public-key", Config.compatibilityReportApiKey);
    }

    @Test
    void compatibilityReportingCanBeDisabled() {
        Config.configureCompatibility(java.util.List.of(), "", "");

        assertEquals("", Config.compatibilityReportEndpoint);
        assertEquals("", Config.compatibilityReportApiKey);
    }

    @Test
    void legacyDirectReportEndpointUsesRateLimitedFunction() {
        Config.configureCompatibility(java.util.List.of(),
            Config.LEGACY_COMPATIBILITY_REPORT_ENDPOINT, "public-key");

        assertEquals(Config.COMPATIBILITY_REPORT_FUNCTION_ENDPOINT,
            Config.compatibilityReportEndpoint);
    }

    @Test
    void compatibilityRulesCanBeDisabledOrRedirected() {
        Config.configureCompatibilityRules(" https://example.invalid/rules ", " read-key ");

        assertEquals("https://example.invalid/rules", Config.compatibilityRulesEndpoint);
        assertEquals("read-key", Config.compatibilityRulesApiKey);
    }
}

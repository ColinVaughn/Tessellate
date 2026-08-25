package org.texboobcat.tessellate;

import org.texboobcat.tessellate.api.TessellateApiInternal;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class Config {

    public static final List<String> DEFAULT_MAIN_THREAD_ENTITIES =
        List.of("creaturefeature:toadstool");
    public static final String DEFAULT_COMPATIBILITY_RULES_ENDPOINT =
        "https://kuisavsedtdbmuroharj.supabase.co/rest/v1/"
            + "tessellate_entity_compatibility_rules";
    public static final String DEFAULT_COMPATIBILITY_FEATURE_RULES_ENDPOINT =
        "https://kuisavsedtdbmuroharj.supabase.co/rest/v1/"
            + "tessellate_mod_compatibility_rules";
    public static final String COMPATIBILITY_REPORT_FUNCTION_ENDPOINT =
        "https://kuisavsedtdbmuroharj.supabase.co/functions/v1/tessellate-report";
    public static final String LEGACY_COMPATIBILITY_REPORT_ENDPOINT =
        "https://kuisavsedtdbmuroharj.supabase.co/rest/v1/"
            + "tessellate_compatibility_reports";
    public static final String DEFAULT_COMPATIBILITY_RULES_API_KEY =
        "sb_publishable_9rG9js2JGBznNNHWKVphpQ__IRClqQd";
    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_]{1,63}");

    private static final Values DEFAULTS = new Values(
        true, 2, 2, 200,
        true, true, true, true,
        true, 25.0, 45.0, 2.0, 16,
        true, false, true, true, true, true,
        0, true, false, DEFAULT_MAIN_THREAD_ENTITIES, false, false, false);

    public static boolean regionsEnabled;
    public static int sectionShift;
    public static int mergeRadius;
    public static int verifyIntervalTicks;
    public static boolean scopedEntityTicking;
    public static boolean scopedBlockEntityTicking;
    public static boolean scopedScheduledTicks;
    public static boolean scopedBlockEvents;
    public static boolean adaptiveThrottling;
    public static double regionBudgetMillis;
    public static double targetTickMillis;
    public static double minThrottleMillis;
    public static int maxTickDivisor;
    public static boolean shardEntityStorage;
    public static boolean assertShardOwnership;
    public static boolean parallelTicking;
    public static boolean directWorkerChunkReads;
    public static boolean parallelNaturalSpawning;
    public static boolean asyncRegionLoops;
    public static int workerThreads;
    public static boolean threadLocalRandom;
    public static boolean serializeEntityTicks;
    public static List<String> mainThreadEntities;
    public static boolean diagnoseEntitySectionRaces;
    public static boolean strictGuard;
    public static boolean logRegionChanges;
    public static List<String> forceSerialMods;
    public static String compatibilityReportEndpoint;
    public static String compatibilityReportApiKey;
    public static String compatibilityRulesEndpoint;
    public static String compatibilityRulesApiKey;

    static {
        reset();
    }

    private Config() {
    }

    public static boolean parallelTickingConfigured() {
        return parallelTicking && shardEntityStorage && threadLocalRandom;
    }

    public static void reset() {
        apply(DEFAULTS);
        configureCompatibility(List.of(), COMPATIBILITY_REPORT_FUNCTION_ENDPOINT,
            DEFAULT_COMPATIBILITY_RULES_API_KEY);
        configureCompatibilityRules(DEFAULT_COMPATIBILITY_RULES_ENDPOINT,
            DEFAULT_COMPATIBILITY_RULES_API_KEY);
    }

    public static void configureCompatibility(List<String> serialMods, String reportEndpoint,
                                              String reportApiKey) {
        forceSerialMods = Objects.requireNonNull(serialMods, "serialMods").stream()
            .filter(Objects::nonNull)
            .filter(id -> MOD_ID.matcher(id).matches())
            .distinct()
            .toList();
        String endpoint = Objects.requireNonNull(reportEndpoint, "reportEndpoint").strip();
        compatibilityReportEndpoint = LEGACY_COMPATIBILITY_REPORT_ENDPOINT.equals(endpoint)
            ? COMPATIBILITY_REPORT_FUNCTION_ENDPOINT : endpoint;
        compatibilityReportApiKey = Objects.requireNonNull(reportApiKey, "reportApiKey").strip();
    }

    public static void configureCompatibilityRules(String endpoint, String apiKey) {
        compatibilityRulesEndpoint = Objects.requireNonNull(endpoint, "endpoint").strip();
        compatibilityRulesApiKey = Objects.requireNonNull(apiKey, "apiKey").strip();
    }

    public static void apply(final Values values) {
        Objects.requireNonNull(values, "values");
        regionsEnabled = values.regionsEnabled();
        sectionShift = values.sectionShift();
        mergeRadius = values.mergeRadius();
        verifyIntervalTicks = values.verifyIntervalTicks();
        scopedEntityTicking = values.scopedEntityTicking();
        scopedBlockEntityTicking = values.scopedBlockEntityTicking();
        scopedScheduledTicks = values.scopedScheduledTicks();
        scopedBlockEvents = values.scopedBlockEvents();
        adaptiveThrottling = values.adaptiveThrottling();
        regionBudgetMillis = values.regionBudgetMillis();
        targetTickMillis = values.targetTickMillis();
        minThrottleMillis = values.minThrottleMillis();
        maxTickDivisor = values.maxTickDivisor();
        shardEntityStorage = values.shardEntityStorage();
        assertShardOwnership = values.assertShardOwnership();
        parallelTicking = values.parallelTicking();
        directWorkerChunkReads = values.directWorkerChunkReads();
        parallelNaturalSpawning = values.parallelNaturalSpawning();
        asyncRegionLoops = values.asyncRegionLoops();
        workerThreads = values.workerThreads();
        threadLocalRandom = values.threadLocalRandom();
        serializeEntityTicks = values.serializeEntityTicks();
        mainThreadEntities = List.copyOf(values.mainThreadEntities());
        TessellateApiInternal.configureMainThreadEntities(mainThreadEntities);
        diagnoseEntitySectionRaces = values.diagnoseEntitySectionRaces();
        strictGuard = values.strictGuard();
        logRegionChanges = values.logRegionChanges();
    }

    public record Values(boolean regionsEnabled, int sectionShift, int mergeRadius,
                         int verifyIntervalTicks, boolean scopedEntityTicking,
                         boolean scopedBlockEntityTicking, boolean scopedScheduledTicks,
                         boolean scopedBlockEvents, boolean adaptiveThrottling,
                         double regionBudgetMillis, double targetTickMillis,
                         double minThrottleMillis, int maxTickDivisor,
                         boolean shardEntityStorage, boolean assertShardOwnership,
                         boolean parallelTicking, boolean directWorkerChunkReads,
                         boolean parallelNaturalSpawning, boolean asyncRegionLoops,
                         int workerThreads, boolean threadLocalRandom,
                         boolean serializeEntityTicks,
                         List<String> mainThreadEntities,
                         boolean diagnoseEntitySectionRaces, boolean strictGuard,
                         boolean logRegionChanges) {
    }
}

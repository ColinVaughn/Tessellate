package org.texboobcat.tessellate;

import java.util.Objects;

public final class Config {

    private static final Values DEFAULTS = new Values(
        true, 2, 2, 200,
        true, true, true, true,
        true, 25.0, 45.0, 2.0, 16,
        true, false, true, true, true, true,
        0, true, false, false, false);

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
    public static boolean diagnoseEntitySectionRaces;
    public static boolean strictGuard;
    public static boolean logRegionChanges;

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
                         boolean diagnoseEntitySectionRaces, boolean strictGuard,
                         boolean logRegionChanges) {
    }
}

package org.texboobcat.optimal;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

// Config for regional ticking.
//
// onLoad is registered explicitly on the mod event bus from the mod constructor rather
// than through @EventBusSubscriber, whose Bus enum is deprecated for removal in
// NeoForge 21.1.
public final class Config {

    private Config() {
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue REGIONS_ENABLED = BUILDER
        .comment("Track entity-ticking chunks as independent regions.")
        .define("regions.enabled", true);

    private static final ModConfigSpec.IntValue SECTION_SHIFT = BUILDER
        .comment(
            "Region section size as a power of two, in chunks. 2 means 4x4-chunk sections (64 blocks).",
            "Larger sections mean fewer, coarser regions and a wider safety buffer between them;",
            "smaller sections mean finer isolation and more merge/split churn.")
        .defineInRange("regions.sectionShift", 2, 0, 8);

    private static final ModConfigSpec.IntValue MERGE_RADIUS = BUILDER
        .comment(
            "Sections within this Chebyshev distance belong to the same region.",
            "Two distinct regions are therefore always more than this many sections apart,",
            "which is the buffer that makes cross-region reads safe.",
            "With sectionShift 2 and mergeRadius 2 the guaranteed buffer is 128 blocks.")
        .defineInRange("regions.mergeRadius", 2, 1, 16);

    private static final ModConfigSpec.IntValue VERIFY_INTERVAL = BUILDER
        .comment(
            "Ticks between full rescans that reconcile the incrementally-tracked ticking-chunk",
            "set against the chunk map. Status changes are tracked incrementally, so this is a",
            "self-heal and cross-check, not the primary mechanism. A rescan costs roughly 1.5",
            "microseconds per loaded chunk, so do not set this low on a large world.",
            "0 disables the check entirely, which is not recommended.")
        .defineInRange("regions.verifyIntervalTicks", 200, 0, 24000);

    private static final ModConfigSpec.BooleanValue SCOPED_ENTITY_TICKING = BUILDER
        .comment(
            "Tick entities region by region instead of in one flat pass over the level.",
            "The work done per entity is unchanged; only the grouping and the order across",
            "regions differ, and each region gets timed separately. With parallel ticking and",
            "sharded storage it joins the complete region worker envelope.",
            "Set false to fall back to the vanilla path, which is how the two are A/B compared.")
        .define("regions.scopedEntityTicking", true);

    private static final ModConfigSpec.BooleanValue SCOPED_BLOCK_ENTITY_TICKING = BUILDER
        .comment(
            "Tick block entities region by region instead of in one flat pass over the level.",
            "With all parallel prerequisites enabled this runs after entities on the same region",
            "worker. Set false to fall back to the vanilla path for A/B comparison.")
        .define("regions.scopedBlockEntityTicking", true);

    private static final ModConfigSpec.BooleanValue SCOPED_SCHEDULED_TICKS = BUILDER
        .comment(
            "Give each current region its own ordinary vanilla LevelTicks scheduler.",
            "Chunk-owned tick containers move between schedulers only at the existing quiescent",
            "topology barrier, preserving their queue, deduplication and save data.",
            "Each region also gets its own maxAllowedTicks budget, so a region saturating the cap",
            "can no longer starve the others. Set false to move every container back to the",
            "single vanilla-compatible fallback scheduler.")
        .define("regions.scopedScheduledTicks", true);

    private static final ModConfigSpec.BooleanValue SCOPED_BLOCK_EVENTS = BUILDER
        .comment(
            "Give each current region its own ordered, deduplicating block-event queue.",
            "Callbacks run after that region's chunk phase and before its entity phase; successful",
            "event packets remain main-thread work. Set false to use one serial fallback queue.")
        .define("regions.scopedBlockEvents", true);

    private static final ModConfigSpec.BooleanValue ADAPTIVE_THROTTLING = BUILDER
        .comment(
            "Tick overloaded regions less often so they cannot push the server past its budget.",
            "",
            "This is what isolates a lag machine. Without it, one region's cost is added to every",
            "server tick and every player pays for it - measured at 6000 mobs, an empty area 1024",
            "blocks away still ran at 11.3 TPS. With it, the expensive region ticks at a reduced",
            "rate and everyone else keeps 20 TPS.",
            "",
            "The cost is that the overloaded region runs in slow motion: its mobs, its",
            "redstone and its hoppers all advance more slowly. That is the intended trade.")
        .define("regions.adaptiveThrottling", true);

    private static final ModConfigSpec.DoubleValue REGION_BUDGET_MILLIS = BUILDER
        .comment(
            "Minimum per-tick budget, in milliseconds, for all region work combined.",
            "The adaptive budget can grow up to targetTickMillis when the rest of the server is",
            "cheap, but never falls below this floor when non-region overhead is high.")
        .defineInRange("regions.budgetMillis", 25.0, 1.0, 45.0);

    private static final ModConfigSpec.DoubleValue TARGET_TICK_MILLIS = BUILDER
        .comment(
            "The server tick time the throttle steers toward, in milliseconds. Regions are slowed",
            "only when the tick would otherwise exceed this.",
            "",
            "This is what decides when the mod intervenes at all. Everything that is not region",
            "work - the chunk system, networking, block entities outside regions - is overhead the",
            "regions cannot control, so their budget is whatever is left of this target after",
            "paying it. On a server comfortably holding 20 TPS the remainder is large and nothing",
            "is throttled.",
            "",
            "Defaults to 45 ms, leaving 5 ms of margin under the 50 ms that 20 TPS allows. Lower",
            "it for more headroom against spikes at the cost of throttling sooner; raise it to let",
            "busy areas run at full speed closer to the limit.")
        .defineInRange("regions.targetTickMillis", 45.0, 5.0, 50.0);

    private static final ModConfigSpec.DoubleValue MIN_THROTTLE_MILLIS = BUILDER
        .comment(
            "Regions cheaper than this are never throttled, whatever the budget says.",
            "Without a floor, measurement noise would throttle ordinary regions that are not the",
            "problem, and players would see stutter the server did not need to introduce.")
        .defineInRange("regions.minThrottleMillis", 2.0, 0.0, 50.0);

    private static final ModConfigSpec.IntValue MAX_TICK_DIVISOR = BUILDER
        .comment(
            "Slowest a region may be throttled to, as a divisor of the tick rate. 20 TPS divided",
            "by 16 is 1.25 TPS. Past this the region is left to overrun the budget rather than",
            "being slowed further, so a broken region cannot be throttled into looking frozen.")
        .defineInRange("regions.maxTickDivisor", 16, 1, 64);

    private static final ModConfigSpec.BooleanValue SHARD_ENTITY_STORAGE = BUILDER
        .comment(
            "Split the level's entity section storage into one shard per region",
            "section, so that two region threads never touch the same container.",
            "",
            "This is the prerequisite for parallel ticking, not an optimization in itself. Vanilla",
            "keeps every entity section in one map plus one balanced tree; a concurrent read of",
            "that tree does not just throw, it can walk into a half-relinked subtree and return",
            "silently wrong results. Sharding is by fixed section grid cell rather than by region,",
            "so regions merging or splitting migrates nothing.",
            "",
            "Expected to measure as a no-op on its own. Disable only when isolating a compatibility",
            "problem or comparing against vanilla storage.")
        .define("regions.shardEntityStorage", true);

    private static final ModConfigSpec.BooleanValue ASSERT_SHARD_OWNERSHIP = BUILDER
        .comment(
            "With shardEntityStorage, check that a region worker only writes entity storage for",
            "cells it owns or is adjacent to, and report anything else through /optimal violations.",
            "",
            "The design argues such a write is impossible: the merge radius keeps distinct regions",
            "at least three cells apart, and one tick of movement spills at most one cell. That is",
            "a bounded argument, not an unconditional one, so this turns a wrong assumption into a",
            "logged violation instead of silent corruption. Development aid; writes only, since",
            "the read path is the hottest in the game.")
        .define("regions.assertShardOwnership", false);

    private static final ModConfigSpec.BooleanValue PARALLEL_TICKING = BUILDER
        .comment(
            "Tick regions on worker threads instead of sequentially on the main",
            "thread. Regions cannot interact - they are always more than mergeRadius sections",
            "apart - and each task includes scheduled ticks, chunk/random ticks, entities and",
            "block entities, block-event callbacks, and natural-spawn searches. Lifecycle,",
            "network, cross-region, and other global commits stay on the main thread.",
            "On the first unsafe access the server",
            "permanently falls back to serial",
            "ticking for the rest of the session and logs why; see /optimal regions.")
        .define("regions.parallelTicking", true);

    private static final ModConfigSpec.BooleanValue DIRECT_WORKER_CHUNK_READS = BUILDER
        .comment(
            "Resolve region-worker chunk reads at the Level boundary from already-loaded chunks.",
            "This preserves the same loaded-only safety rule as the ServerChunkCache fallback,",
            "while avoiding third-party synchronous-load instrumentation around that lower call.",
            "Set false only for compatibility isolation or an A/B benchmark.")
        .define("regions.directWorkerChunkReads", true);

    private static final ModConfigSpec.BooleanValue PARALLEL_NATURAL_SPAWNING = BUILDER
        .comment(
            "Run natural-spawn searches for independent regions concurrently.",
            "One level-wide snapshot and atomic global/local cap reservations prevent the",
            "per-region cap multiplication that separate vanilla SpawnStates would cause.",
            "Entity insertion stays on the main thread. A worker or mod-hook failure rolls back",
            "the current reservation and permanently falls back to serial ticking.")
        .define("regions.parallelNaturalSpawning", true);

    private static final ModConfigSpec.BooleanValue ASYNC_REGION_LOOPS = BUILDER
        .comment(
            "Let complete region envelopes survive beyond the server tick that dispatched them.",
            "",
            "A busy region keeps its claim and misses only its own next slot. Main-thread entity",
            "loading, packets, commands, chunk-status changes and saves lease only the affected",
            "region; topology changes block only their merge neighborhood.")
        .define("regions.asyncRegionLoops", true);

    private static final ModConfigSpec.IntValue WORKER_THREADS = BUILDER
        .comment(
            "Region worker threads. 0 means (available processors - 2), leaving room for the",
            "main thread and the chunk system.")
        .defineInRange("regions.workerThreads", 0, 0, 64);

    private static final ModConfigSpec.BooleanValue THREAD_LOCAL_RANDOM = BUILDER
        .comment(
            "Give each thread its own Level.random and block-random LCG state. Vanilla keeps both",
            "as unsynchronised mutable level fields, so concurrent draws corrupt each other.",
            "Required for parallelTicking; harmless without it.")
        .define("regions.threadLocalRandom", true);

    private static final ModConfigSpec.BooleanValue DIAGNOSE_ENTITY_SECTION_RACES = BUILDER
        .comment(
            "Report the main-thread call sites that mutate entity storage while a region worker",
            "is running. Development aid for the async work; silent unless parallelTicking is on.")
        .define("guard.diagnoseEntitySectionRaces", false);

    private static final ModConfigSpec.BooleanValue STRICT_GUARD = BUILDER
        .comment(
            "Ownership violations throw instead of being logged. Use in development and gametests.",
            "Production should leave this false so a violation degrades to serial ticking",
            "rather than crashing the server.")
        .define("guard.strict", false);

    private static final ModConfigSpec.BooleanValue LOG_REGION_CHANGES = BUILDER
        .comment("Log every region create/merge/split/destroy. Very noisy; development only.")
        .define("guard.logRegionChanges", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean regionsEnabled = true;
    public static int sectionShift = 2;
    public static int mergeRadius = 2;
    public static int verifyIntervalTicks = 200;
    public static boolean scopedEntityTicking = true;
    public static boolean scopedBlockEntityTicking = true;
    public static boolean scopedScheduledTicks = true;
    public static boolean scopedBlockEvents = true;
    public static boolean adaptiveThrottling = true;
    public static double regionBudgetMillis = 25.0;
    public static double targetTickMillis = 45.0;
    public static double minThrottleMillis = 2.0;
    public static int maxTickDivisor = 16;
    public static boolean shardEntityStorage = true;
    public static boolean assertShardOwnership = false;
    public static boolean parallelTicking = true;
    public static boolean directWorkerChunkReads = true;
    public static boolean parallelNaturalSpawning = true;
    public static boolean asyncRegionLoops = true;
    public static int workerThreads = 0;
    public static boolean threadLocalRandom = true;
    public static boolean diagnoseEntitySectionRaces = false;
    public static boolean strictGuard = false;
    public static boolean logRegionChanges = false;

    public static boolean parallelTickingConfigured() {
        return parallelTicking && shardEntityStorage && threadLocalRandom;
    }

    public static void onLoad(final ModConfigEvent event) {
        // ModConfigEvent fires for every mod's config, not just ours.
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        regionsEnabled = REGIONS_ENABLED.get();
        sectionShift = SECTION_SHIFT.get();
        mergeRadius = MERGE_RADIUS.get();
        verifyIntervalTicks = VERIFY_INTERVAL.get();
        scopedEntityTicking = SCOPED_ENTITY_TICKING.get();
        scopedBlockEntityTicking = SCOPED_BLOCK_ENTITY_TICKING.get();
        scopedScheduledTicks = SCOPED_SCHEDULED_TICKS.get();
        scopedBlockEvents = SCOPED_BLOCK_EVENTS.get();
        adaptiveThrottling = ADAPTIVE_THROTTLING.get();
        regionBudgetMillis = REGION_BUDGET_MILLIS.get();
        targetTickMillis = TARGET_TICK_MILLIS.get();
        minThrottleMillis = MIN_THROTTLE_MILLIS.get();
        maxTickDivisor = MAX_TICK_DIVISOR.get();
        shardEntityStorage = SHARD_ENTITY_STORAGE.get();
        assertShardOwnership = ASSERT_SHARD_OWNERSHIP.get();
        parallelTicking = PARALLEL_TICKING.get();
        directWorkerChunkReads = DIRECT_WORKER_CHUNK_READS.get();
        parallelNaturalSpawning = PARALLEL_NATURAL_SPAWNING.get();
        asyncRegionLoops = ASYNC_REGION_LOOPS.get();
        workerThreads = WORKER_THREADS.get();
        threadLocalRandom = THREAD_LOCAL_RANDOM.get();
        diagnoseEntitySectionRaces = DIAGNOSE_ENTITY_SECTION_RACES.get();
        strictGuard = STRICT_GUARD.get();
        logRegionChanges = LOG_REGION_CHANGES.get();
    }
}

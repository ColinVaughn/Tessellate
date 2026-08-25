package org.texboobcat.tessellate.neoforge;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.texboobcat.tessellate.Config;

// Config for regional ticking.
//
// onLoad is registered explicitly on the mod event bus from the mod constructor rather
// than through @EventBusSubscriber, whose Bus enum is deprecated for removal in
// NeoForge 21.1.
public final class TessellateNeoForgeConfig {

    private TessellateNeoForgeConfig() {
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
            "cells it owns or is adjacent to, and report anything else through /tessellate violations.",
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
            "ticking for the rest of the session and logs why; see /tessellate regions.")
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

    private static final ModConfigSpec.BooleanValue SERIALIZE_ENTITY_TICKS = BUILDER
        .comment(
            "Prevent entity tick bodies from overlapping across region workers.",
            "This keeps other regional systems parallel and is a compatibility fallback for",
            "mods with shared mutable entity-tick state. Prefer upstream thread-safety fixes.")
        .define("compatibility.serializeEntityTicks", false);

    private static final ModConfigSpec.ConfigValue<java.util.List<? extends String>>
            MAIN_THREAD_ENTITIES = BUILDER
        .comment(
            "Entity type IDs that must tick on the main thread for compatibility with mods",
            "that have not adopted Tessellate's API. Example: creaturefeature:toadstool")
        .defineListAllowEmpty("compatibility.mainThreadEntities",
            Config.DEFAULT_MAIN_THREAD_ENTITIES, () -> "minecraft:pig",
            value -> value instanceof String string
                && net.minecraft.resources.ResourceLocation.tryParse(string) != null);

    private static final ModConfigSpec.ConfigValue<java.util.List<? extends String>>
            FORCE_SERIAL_MODS = BUILDER
        .comment(
            "Mod IDs that force serial region ticking when loaded.",
            "Use this as a temporary compatibility override while the underlying mod is patched.")
        .defineListAllowEmpty("compatibility.forceSerialMods", java.util.List.of(),
            () -> "example_mod", value -> value instanceof String string
                && string.matches("[a-z][a-z0-9_]{1,63}"));

    private static final ModConfigSpec.ConfigValue<String> REPORT_ENDPOINT = BUILDER
        .comment(
            "Optional HTTPS endpoint for structured compatibility reports. Blank disables uploads.",
            "Use the rate-limited Tessellate Edge Function; direct table writes are disabled.")
        .define("compatibility.reportEndpoint", "");

    private static final ModConfigSpec.ConfigValue<String> REPORT_API_KEY = BUILDER
        .comment("Optional public/anon API key for the compatibility report endpoint.",
            "Never put a Supabase service-role key here.")
        .define("compatibility.reportApiKey", "");

    private static final ModConfigSpec.ConfigValue<String> RULES_ENDPOINT = BUILDER
        .comment(
            "HTTPS endpoint for curated entity compatibility rules.",
            "Blank disables remote rules without enabling or disabling failure reporting.")
        .define("compatibility.rulesEndpoint",
            Config.DEFAULT_COMPATIBILITY_RULES_ENDPOINT);

    private static final ModConfigSpec.ConfigValue<String> RULES_API_KEY = BUILDER
        .comment("Public/anon API key for the read-only compatibility rules endpoint.",
            "Never put a Supabase service-role key here.")
        .define("compatibility.rulesApiKey", Config.DEFAULT_COMPATIBILITY_RULES_API_KEY);

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

    public static void onLoad(final ModConfigEvent event) {
        // ModConfigEvent fires for every mod's config, not just ours.
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        Config.apply(new Config.Values(
            REGIONS_ENABLED.get(), SECTION_SHIFT.get(), MERGE_RADIUS.get(), VERIFY_INTERVAL.get(),
            SCOPED_ENTITY_TICKING.get(), SCOPED_BLOCK_ENTITY_TICKING.get(),
            SCOPED_SCHEDULED_TICKS.get(), SCOPED_BLOCK_EVENTS.get(), ADAPTIVE_THROTTLING.get(),
            REGION_BUDGET_MILLIS.get(), TARGET_TICK_MILLIS.get(), MIN_THROTTLE_MILLIS.get(),
            MAX_TICK_DIVISOR.get(), SHARD_ENTITY_STORAGE.get(), ASSERT_SHARD_OWNERSHIP.get(),
            PARALLEL_TICKING.get(), DIRECT_WORKER_CHUNK_READS.get(),
            PARALLEL_NATURAL_SPAWNING.get(), ASYNC_REGION_LOOPS.get(), WORKER_THREADS.get(),
            THREAD_LOCAL_RANDOM.get(), SERIALIZE_ENTITY_TICKS.get(),
            MAIN_THREAD_ENTITIES.get().stream().map(String::valueOf).toList(),
            DIAGNOSE_ENTITY_SECTION_RACES.get(), STRICT_GUARD.get(),
            LOG_REGION_CHANGES.get()));
        Config.configureCompatibility(
            FORCE_SERIAL_MODS.get().stream().map(String::valueOf).toList(),
            REPORT_ENDPOINT.get(), REPORT_API_KEY.get());
        Config.configureCompatibilityRules(RULES_ENDPOINT.get(), RULES_API_KEY.get());
    }
}

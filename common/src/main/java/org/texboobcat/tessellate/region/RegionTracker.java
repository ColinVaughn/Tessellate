package org.texboobcat.tessellate.region;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.texboobcat.tessellate.Config;
import org.texboobcat.tessellate.CompatibilityReporter;
import org.slf4j.Logger;
import org.texboobcat.tessellate.guard.ViolationLog;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Server-wide registry of per-level region indexes, plus the shared violation log.
public final class RegionTracker {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<ResourceKey<Level>, LevelRegionIndex> INDEXES = new ConcurrentHashMap<>();
    private static final ViolationLog VIOLATIONS = new ViolationLog();

    private RegionTracker() {
    }

    private static volatile long serverTickNanos;

    // The server's own smoothed tick time, and what regions cost inside it.
    //
    // Summed across levels rather than taken per level: the throttle is deciding how to share
    // one server tick, and a level that looks at its own work alone would treat every other level's
    // cost as headroom it could spend.
    public static long serverTickNanos() {
        return serverTickNanos;
    }

    public static void recordServerTickNanos(long nanos) {
        serverTickNanos = nanos;
    }

    public static long regionWorkNanos() {
        long total = 0L;
        for (LevelRegionIndex index : INDEXES.values()) {
            for (Region region : index.regionizer().regions()) {
                total += region.lastTickNanos() + region.lastBlockEntityNanos();
            }
        }
        return total;
    }

    public static ViolationLog violations() {
        return VIOLATIONS;
    }

    public static void onLevelLoad(ServerLevel level) {
        if (!Config.regionsEnabled) {
            return;
        }
        LevelRegionIndex index = new LevelRegionIndex(level);
        INDEXES.put(level.dimension(), index);

        // The sharded storage is built inside the entity manager's constructor, which knows
        // nothing about its level. Binding it here is what lets a storage write ask whether the
        // region owning that cell is currently ticking.
        RegionShardedEntityStorage<?> sharded =
            EntityStorageBinding.shardedStorageOf(level);
        if (sharded != null) {
            sharded.bindIndex(index);
        }
    }

    public static void onLevelUnload(ServerLevel level) {
        LevelRegionIndex index = INDEXES.get(level.dimension());
        if (index != null) {
            MainThreadBoundaries.measure(MainThreadBoundaries.Boundary.TOPOLOGY_BARRIER,
                MainThreadBoundaries.globalSource(index.levelKey()),
                RegionTracker::quiesceAndDrain);
        }
        INDEXES.remove(level.dimension());
    }

    // A chunk crossed the entity-ticking threshold. Main thread, from ChunkMapMixin.
    //
    // This fires during level load too, before onLevelLoad has created the index, so
    // a missing index is normal rather than an error. The periodic verify rescan reconciles
    // anything that arrived in that window.
    public static void onChunkStatusChange(ServerLevel level, long chunkPos, boolean entityTicking) {
        if (!Config.regionsEnabled) {
            return;
        }
        LevelRegionIndex index = INDEXES.get(level.dimension());
        if (index != null) {
            index.onChunkStatusChange(chunkPos, entityTicking);
        }
    }

    public static void onLevelTick(ServerLevel level) {
        if (!Config.regionsEnabled) {
            return;
        }
        LevelRegionIndex index = INDEXES.get(level.dimension());
        if (index != null) {
            index.tick();
            if (level.getBlockTicks() instanceof RegionalLevelTicks<?> blockTicks) {
                blockTicks.reconcile(index);
            }
            if (level.getFluidTicks() instanceof RegionalLevelTicks<?> fluidTicks) {
                fluidTicks.reconcile(index);
            }
        }
    }

    @Nullable
    public static LevelRegionIndex index(ServerLevel level) {
        return INDEXES.get(level.dimension());
    }

    public static Collection<LevelRegionIndex> indexes() {
        return INDEXES.values();
    }

    // Commands, saves and shutdown all need the same consistent-world boundary.
    public static void quiesceAndDrain() {
        if (RegionWorkers.anyTaskInFlight()) {
            RegionWorkers.awaitIdle();
        }
        for (LevelRegionIndex index : java.util.List.copyOf(INDEXES.values())) {
            index.awaitAllRegions();
            index.drainDeferredCallbacks();
        }
        DeferredMainThreadWork.drain();
    }

    // Used by deferred handoffs to avoid replaying into a region that still owns its tick.
    public static boolean regionIdle(String levelKey, int regionId) {
        for (LevelRegionIndex index : INDEXES.values()) {
            if (index.levelKey().equals(levelKey)) {
                return index.regionIdle(regionId);
            }
        }
        return true;
    }

    // Whether parallel region ticking is still permitted this session.
    //
    // Set false permanently by degradeToSerial. Volatile because worker threads set it
    // and the main thread reads it at the start of every tick.
    private static volatile boolean parallelAllowed = true;
    private static volatile String degradeReason;
    // How long parallel ticking stays off after a worker meets an unloaded chunk.
    private static final long CHUNK_MISS_PAUSE_MILLIS = Long.getLong("tessellate.chunkMissPauseMs", 5000L);
    private static final java.util.concurrent.atomic.AtomicLong UNAVAILABLE_CHUNKS =
        new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong DEFERRED_AUTOSAVES =
        new java.util.concurrent.atomic.AtomicLong();

    // Non-zero while parallel ticking is only paused (e.g. chunks are still being generated); the
    // nanoTime after which it resumes. Zero means a permanent degrade or no degrade at all.
    private static volatile long resumeAtNanos;

    public static boolean parallelAllowed() {
        if (parallelAllowed) {
            return true;
        }
        long resume = resumeAtNanos;
        if (resume != 0L && System.nanoTime() - resume >= 0L) {
            resumeAtNanos = 0L;
            degradeReason = null;
            parallelAllowed = true;
            return true;
        }
        return false;
    }

    // Pauses parallel ticking for a while instead of for the whole session. For conditions that
    // pass by themselves, such as a worker meeting a chunk that is still being generated; a real
    // safety violation still degrades permanently, even while a pause is running.
    public static void pauseParallel(String reason, long millis) {
        if (parallelAllowed) {
            degradeReason = reason + " (paused " + millis + " ms)";
            resumeAtNanos = Math.max(1L, System.nanoTime() + millis * 1_000_000L);
            parallelAllowed = false;
            LOGGER.info("tessellate: parallel ticking paused for {} ms: {}", millis, reason);
        }
    }

    @Nullable
    public static String degradeReason() {
        return degradeReason;
    }

    public static long unavailableChunks() {
        return UNAVAILABLE_CHUNKS.get();
    }

    public static void recordDeferredAutosave() {
        DEFERRED_AUTOSAVES.incrementAndGet();
    }

    public static long deferredAutosaves() {
        return DEFERRED_AUTOSAVES.get();
    }

    // Permanently stops parallel ticking for this session.
    //
    // Deliberately one-way. A condition that made concurrent ticking unsafe once will recur,
    // and flapping between modes would make the failure far harder to diagnose than simply
    // running single-threaded for the rest of the session.
    public static void degradeToSerial(String reason) {
        degradeToSerial("unknown_safety_check", reason, null);
    }

    public static void degradeToSerial(String reason, @Nullable Throwable failure) {
        degradeToSerial(failure == null ? "unknown_safety_check" : "worker_exception",
            reason, failure);
    }

    public static void degradeToSerial(String reasonCode, String reason) {
        degradeToSerial(reasonCode, reason, null);
    }

    private static void degradeToSerial(String reasonCode, String reason,
                                        @Nullable Throwable failure) {
        if (parallelAllowed || resumeAtNanos != 0L) {
            resumeAtNanos = 0L;
            parallelAllowed = false;
            degradeReason = reason;
            CompatibilityReporter.report("region-ticking", "serial-fallback",
                "falling back to serial region ticking for this session: " + reason, failure,
                reasonCode);
        }
    }

    public static void forceSerial(String reason) {
        if (parallelAllowed || resumeAtNanos != 0L) {
            resumeAtNanos = 0L;
            parallelAllowed = false;
            degradeReason = reason;
            LOGGER.warn("tessellate: using serial region ticking: {}", reason);
        }
    }

    // A worker asked for a chunk that was not already loaded.
    //
    // It cannot fetch one without blocking on the main thread, which is waiting for it, so the
    // only safe response is to stop ticking in parallel.
    public static void reportUnavailableChunk(int chunkX, int chunkZ, Object status) {
        UNAVAILABLE_CHUNKS.incrementAndGet();
        pauseParallel("a region worker needed chunk [" + chunkX + ", " + chunkZ + "] at status "
            + status + ", which was not loaded (still generating?)", CHUNK_MISS_PAUSE_MILLIS);
    }

    public static void reset() {
        INDEXES.clear();
        VIOLATIONS.clear();
        parallelAllowed = true;
        resumeAtNanos = 0L;
        degradeReason = null;
        UNAVAILABLE_CHUNKS.set(0);
        DeferredMainThreadWork.reset();
        PhaseStats.reset();
        MainThreadBoundaries.reset();
        ParallelNaturalSpawner.reset();
    }
}

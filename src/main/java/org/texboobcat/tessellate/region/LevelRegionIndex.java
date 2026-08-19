package org.texboobcat.tessellate.region;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.texboobcat.tessellate.Config;
import org.texboobcat.tessellate.guard.Ownership;
import org.texboobcat.tessellate.guard.RegionThreadContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

// Drives one level's Regionizer from the live set of entity-ticking chunks.
//
// Status changes arrive incrementally from ChunkMapMixin. A full cross-check still runs
// every Config.verifyIntervalTicks ticks, but only as a self-heal: it repairs any drift
// and logs a discrepancy, so a missed transition surfaces as a warning rather than as a silently
// wrong region map. It reads vanilla's confirmed entity-visibility map rather than ticket levels,
// which lead asynchronous chunk promotions and demotions. A full scan costs roughly 1.5 us
// per loaded chunk, which is fine
// occasionally and far too expensive every tick.
public final class LevelRegionIndex implements RegionizerListener {

    // Methods added to Level by LevelMixin.
    public interface RegionalLevelAccess {
        void tessellate$runVanillaBlockEntityPass();
        Map<Integer, List<TickingBlockEntity>> tessellate$prepareRegionalBlockEntities(
            LevelRegionIndex index);
        void tessellate$tickRegionalBlockEntities(@Nullable Region region,
                                               List<TickingBlockEntity> tickers);
        void tessellate$finishRegionalBlockEntities();
    }

    // Methods added to ServerChunkCache by ServerChunkCacheMixin.
    public interface RegionalChunkAccess {
        void tessellate$prepareRegionalChunks(LevelRegionIndex index);
        RegionalChunkWork tessellate$regionalChunkWork();
        @Nullable ChunkAccess tessellate$getChunkForWorker(int x, int z, ChunkStatus status);
    }

    // Immutable chunk work captured before a region task is dispatched.
    public record RegionalChunkWork(
        Consumer<RegionRun> tickRegion,
        Runnable tickUnowned,
        Consumer<Region> finishRegion,
        Runnable finishGlobal) {
    }

    // Values a surviving task must not reread from next tick's mutable region state.
    public record RegionRun(Region region, int divisor, int slice) {
        public boolean includes(int key) {
            return this.slice < 0 || Math.floorMod(key, this.divisor) == this.slice;
        }
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ServerLevel level;
    private final String levelKey;
    private final Regionizer regionizer;

    // Chunks currently believed to be entity-ticking.
    private final LongSet tickingChunks = new LongOpenHashSet();
    private final LongSet scratch = new LongOpenHashSet();

    // Per-region tick state, keyed by region id.
    private final Int2ObjectMap<RegionTickState> states = new Int2ObjectOpenHashMap<>();

    // Entities that are ticking but whose chunk maps to no region.
    //
    // This should stay empty. It exists so that a gap in region tracking can never silently
    // stop an entity from ticking. Orphans are ticked last and reported.
    private final RegionTickState orphans = new RegionTickState(-1);

    // Entity lifecycle callbacks a worker raised, replayed on the main thread.
    private final DeferredEntityCallbacks deferredCallbacks = new DeferredEntityCallbacks();

    // Resolved lazily: the level is not fully built when this index is created.
    private net.minecraft.world.level.entity.LevelCallback<Entity> levelCallbacks;

    private int ticksSinceVerify;
    private long lastUpdateNanos;
    private long lastVerifyNanos;
    private long peakVerifyNanos;
    private boolean verified;
    private long discrepancies;
    private long entityDiscrepancies;
    private long lastEntityTickNanos;
    private long lastBlockEntityGroupNanos;
    private int orphanBlockEntityCount;
    private int lastParallelRegions;
    private int lastSkippedRegions;
    private int lastThrottledRegions;
    private long serverTick;
    private long topologyVersion;
    private final List<ScheduledDrain> scheduledDrains = new ArrayList<>(2);
    private final RegionalBlockEvents blockEvents;
    private Runnable blockEventDrain;
    private boolean fullRegionTickExecuted;
    private static final ThreadLocal<Boolean> UNOWNED_TICK_PASS = new ThreadLocal<>();
    private final java.util.Set<String> unplaceableTickerTypes = new java.util.HashSet<>();
    // Regions touched by queued topology changes are not dispatched before the change applies.
    private final it.unimi.dsi.fastutil.ints.IntOpenHashSet topologyBlocked =
        new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

    private record ScheduledDrain(IntConsumer body, int fullBudget) {
    }

    private record Claimed(RegionRun run, RegionTickState state) {
    }

    private record Claims(List<Claimed> regions, int skipped, int throttled) {
    }

    // Identity-keyed; see positionOf. Bounded by the number of live tickers.
    private final Reference2ObjectMap<TickingBlockEntity, BlockPos> tickerPositions =
        new Reference2ObjectOpenHashMap<>();

    public LevelRegionIndex(ServerLevel level) {
        this.level = level;
        this.levelKey = level.dimension().location().toString();
        this.regionizer = new Regionizer(Config.sectionShift, Config.mergeRadius, this);
        this.blockEvents = new RegionalBlockEvents(level, this);
    }

    public ServerLevel level() {
        return this.level;
    }

    public String levelKey() {
        return this.levelKey;
    }

    public Regionizer regionizer() {
        return this.regionizer;
    }

    public long topologyVersion() {
        return this.topologyVersion;
    }

    public RegionalBlockEvents blockEvents() {
        return this.blockEvents;
    }

    public int tickingChunkCount() {
        return this.tickingChunks.size();
    }

    public long lastUpdateNanos() {
        return this.lastUpdateNanos;
    }

    public long lastVerifyNanos() {
        return this.lastVerifyNanos;
    }

    public long peakVerifyNanos() {
        return this.peakVerifyNanos;
    }

    public long discrepancies() {
        return this.discrepancies;
    }

    public long entityDiscrepancies() {
        return this.entityDiscrepancies;
    }

    public long lastEntityTickNanos() {
        return this.lastEntityTickNanos;
    }

    public int orphanEntityCount() {
        return this.orphans.entityCount();
    }

    @Nullable
    public RegionTickState stateFor(Region region) {
        return this.states.get(region.id());
    }

    // ---- per-region entity state -----------------------------------------------------------

    // Entity entered the level's tick list. Main thread, from EntityCallbacksMixin.
    public void onEntityStartTicking(Entity entity) {
        stateForEntity(entity).addEntity(entity);
    }

    // Entity left the level's tick list.
    public void onEntityStopTicking(Entity entity) {
        RegionTickState owner = findOwner(entity);
        if (owner != null) {
            owner.removeEntity(entity);
        }
    }

    // Entity moved to a different chunk section, so it may have crossed a region boundary.
    //
    // Only entities already in the tick list are relocated; a section change for an entity
    // that is not ticking is not this index's business.
    public void onEntitySectionChange(Entity entity) {
        RegionTickState owner = findOwner(entity);
        if (owner == null) {
            return;
        }
        RegionTickState target = stateForEntity(entity);
        if (owner != target) {
            owner.removeEntity(entity);
            target.addEntity(entity);
        }
    }

    // The state whose region owns this entity's chunk, or the orphan bucket.
    private RegionTickState stateForEntity(Entity entity) {
        Region region = this.regionizer.regionForChunkLong(entity.chunkPosition().toLong());
        if (region == null) {
            return this.orphans;
        }
        return this.states.computeIfAbsent(region.id(), RegionTickState::new);
    }

    // Where the entity currently lives, searching by identity rather than by position.
    @Nullable
    private RegionTickState findOwner(Entity entity) {
        if (this.orphans.containsEntity(entity)) {
            return this.orphans;
        }
        // The common case: it is where its position says it should be.
        Region region = this.regionizer.regionForChunkLong(entity.chunkPosition().toLong());
        if (region != null) {
            RegionTickState state = this.states.get(region.id());
            if (state != null && state.containsEntity(entity)) {
                return state;
            }
        }
        for (RegionTickState state : this.states.values()) {
            if (state.containsEntity(entity)) {
                return state;
            }
        }
        return null;
    }

    // Ticks every entity, region by region, timing each region separately.
    //
    // Called from ServerLevelMixin in place of entityTickList.forEach. The
    // consumer is vanilla's own per-entity lambda, so the work done per entity is unchanged; only
    // the grouping and the order across regions differ.
    //
    // Every region is snapshotted before any region is ticked. An entity that crosses a region
    // boundary partway through the phase is therefore ticked exactly once, which is the same
    // guarantee vanilla gets from EntityTickList's active/passive swap.
    public void tickEntitiesByRegion(Consumer<Entity> consumer) {
        if (usesFullRegionTick()) {
            tickFullRegions(consumer, true);
            return;
        }
        long start = System.nanoTime();

        boolean canParallelise = Config.parallelTickingConfigured()
            && RegionTracker.parallelAllowed()
            && RegionWorkers.isRunning();
        List<RegionTickState> ordered = new ArrayList<>(this.states.size());
        int throttled = snapshotEntityStates(ordered);
        this.lastSkippedRegions = 0;
        this.lastThrottledRegions = throttled;

        List<Entity> orphanSnapshot = this.orphans.entityCount() > 0
            ? this.orphans.snapshot()
            : List.of();

        tickEntityStates(ordered, consumer, canParallelise);

        // Orphans always run on the calling thread, last. They belong to no region, so no worker
        // owns them and nothing else can be ticking them.
        tickOrphanEntities(orphanSnapshot, consumer);
        this.orphans.clearSnapshot();

        // With a barrier the workers are idle here, so replaying now keeps lifecycle updates in
        // the same tick they were raised rather than the next one.
        if (!RegionWorkers.anyTaskInFlight()) {
            this.drainDeferredCallbacks();
        }

        this.lastEntityTickNanos = System.nanoTime() - start;
    }

    // True while the complete region envelope is available.
    public boolean usesFullRegionTick() {
        return Config.parallelTickingConfigured()
            && Config.scopedEntityTicking
            && Config.scopedBlockEntityTicking
            && Config.scopedScheduledTicks
            && this.blockEvents.regionalRouting()
            && RegionTracker.parallelAllowed()
            && RegionWorkers.isRunning();
    }

    public void captureScheduledDrain(IntConsumer body, int fullBudget) {
        this.scheduledDrains.add(new ScheduledDrain(body, fullBudget));
    }

    public void captureBlockEventDrain(Runnable body) {
        this.blockEventDrain = body;
    }

    public boolean fullRegionTickExecuted() {
        return this.fullRegionTickExecuted;
    }

    // Runs scheduled/chunk work even when vanilla suppresses the idle entity phase.
    public void finishFullRegionTickIfNeeded() {
        if (usesFullRegionTick() && !this.fullRegionTickExecuted) {
            tickFullRegions(entity -> { }, false);
        }
    }

    // One task per region, with the region bound for the whole spatial tick envelope.
    private void tickFullRegions(Consumer<Entity> entityConsumer, boolean tickEntityPhase) {
        this.fullRegionTickExecuted = true;
        long start = System.nanoTime();

        RegionalLevelAccess levelAccess = (RegionalLevelAccess) this.level;
        RegionalChunkAccess chunkAccess = (RegionalChunkAccess) this.level.getChunkSource();
        RegionalChunkWork chunkWork = chunkAccess.tessellate$regionalChunkWork();
        Map<Integer, List<TickingBlockEntity>> blockEntities = tickEntityPhase
            ? levelAccess.tessellate$prepareRegionalBlockEntities(this)
            : Map.of();
        List<ScheduledDrain> drains = List.copyOf(this.scheduledDrains);
        Runnable eventDrain = this.blockEventDrain;

        List<Region> regions = new ArrayList<>(this.regionizer.regions());
        if (Config.asyncRegionLoops) {
            tickFullRegionsAsync(regions, drains, blockEntities, entityConsumer, tickEntityPhase,
                levelAccess, chunkWork, eventDrain);
            finishFullRegionTick(start);
            return;
        }

        List<Runnable> tasks = prepareFullRegionTasks(regions, drains, blockEntities,
            entityConsumer, tickEntityPhase, levelAccess, chunkWork, eventDrain);

        List<Entity> orphanSnapshot = tickEntityPhase && this.orphans.entityCount() > 0
            ? this.orphans.snapshot()
            : List.of();

        RegionWorkers.runAllAndWait(tasks);

        tickUnownedRegionWork(drains, chunkWork, eventDrain);
        // Custom spawners are main-thread work. The chunk batch leases their player regions
        // before running them, so prior slow regions do not require a global barrier.
        chunkWork.finishGlobal().run();
        finishUnownedEntityPhase(orphanSnapshot, blockEntities, entityConsumer, tickEntityPhase,
            levelAccess);

        this.drainDeferredCallbacks();
        DeferredMainThreadWork.drain();
        if (tickEntityPhase) {
            levelAccess.tessellate$finishRegionalBlockEntities();
        }
        for (Region region : regions) {
            chunkWork.finishRegion().accept(region);
        }
        finishFullRegionTick(start);
    }

    private List<Runnable> prepareFullRegionTasks(List<Region> regions,
            List<ScheduledDrain> drains, Map<Integer, List<TickingBlockEntity>> blockEntities,
            Consumer<Entity> entityConsumer, boolean tickEntityPhase,
            RegionalLevelAccess levelAccess, RegionalChunkWork chunkWork, Runnable eventDrain) {
        List<Runnable> tasks = new ArrayList<>(regions.size());
        int throttled = 0;
        for (Region region : regions) {
            RegionTickState state = this.states.computeIfAbsent(region.id(), RegionTickState::new);
            if (region.tickDivisor() > 1) {
                throttled++;
            }
            if (tickEntityPhase && state.entityCount() > 0) {
                state.snapshot();
            }
            RegionRun run = runFor(region);
            tasks.add(() -> tickFullRegion(run, state, drains,
                blockEntities.getOrDefault(region.id(), List.of()), entityConsumer,
                tickEntityPhase, levelAccess, chunkWork, eventDrain));
        }
        this.lastThrottledRegions = throttled;
        this.lastParallelRegions = tasks.size();
        this.lastSkippedRegions = 0;
        return tasks;
    }

    private void tickUnownedRegionWork(List<ScheduledDrain> drains, RegionalChunkWork chunkWork,
                                       Runnable eventDrain) {
        runUnownedScheduledDrains(drains);
        chunkWork.tickUnowned().run();
        if (eventDrain != null) {
            eventDrain.run();
        }
    }

    private void finishUnownedEntityPhase(List<Entity> orphanSnapshot,
            Map<Integer, List<TickingBlockEntity>> blockEntities, Consumer<Entity> entityConsumer,
            boolean tickEntityPhase, RegionalLevelAccess levelAccess) {
        if (!tickEntityPhase) {
            return;
        }
        tickOrphanEntities(orphanSnapshot, entityConsumer);
        this.orphans.clearSnapshot();
        levelAccess.tessellate$tickRegionalBlockEntities(null,
            blockEntities.getOrDefault(-1, List.of()));
    }

    private void finishFullRegionTick(long start) {
        this.scheduledDrains.clear();
        this.blockEventDrain = null;
        this.lastEntityTickNanos = System.nanoTime() - start;
    }

    private int snapshotEntityStates(List<RegionTickState> ordered) {
        int throttled = 0;
        for (Region region : this.regionizer.regions()) {
            RegionTickState state = this.states.get(region.id());
            if (state == null || state.entityCount() == 0) {
                continue;
            }
            if (region.tickDivisor() > 1) {
                throttled++;
            }
            state.snapshot();
            ordered.add(state);
        }
        return throttled;
    }

    private void tickEntityStates(List<RegionTickState> ordered, Consumer<Entity> consumer,
                                  boolean canParallelise) {
        if (canParallelise && ordered.size() > 1) {
            List<Runnable> tasks = new ArrayList<>(ordered.size());
            for (RegionTickState state : ordered) {
                Region region = this.regionizer.regionForId(state.regionId());
                tasks.add(() -> tickOneRegion(region, state, consumer));
            }
            this.lastParallelRegions = tasks.size();
            RegionWorkers.runAllAndWait(tasks);
            return;
        }

        this.lastParallelRegions = 0;
        for (RegionTickState state : ordered) {
            tickOneRegion(this.regionizer.regionForId(state.regionId()), state, consumer);
        }
    }

    private static void tickOrphanEntities(List<Entity> orphans, Consumer<Entity> consumer) {
        if (orphans.isEmpty()) {
            return;
        }
        PhaseStats.measure(PhaseStats.Phase.ENTITIES, () -> {
            for (Entity entity : orphans) {
                consumer.accept(entity);
            }
        });
    }

    private void tickFullRegionsAsync(List<Region> regions, List<ScheduledDrain> drains,
                                      Map<Integer, List<TickingBlockEntity>> blockEntities,
                                      Consumer<Entity> entityConsumer, boolean tickEntityPhase,
                                      RegionalLevelAccess levelAccess,
                                      RegionalChunkWork chunkWork, Runnable eventDrain) {
        tickUnownedRegionWork(drains, chunkWork, eventDrain);
        chunkWork.finishGlobal().run();

        List<Entity> orphanSnapshot = tickEntityPhase && this.orphans.entityCount() > 0
            ? new ArrayList<>(this.orphans.snapshot())
            : List.of();
        tickOrphanEntities(orphanSnapshot, entityConsumer);
        this.orphans.clearSnapshot();
        if (tickEntityPhase) {
            levelAccess.tessellate$tickRegionalBlockEntities(null,
                blockEntities.getOrDefault(-1, List.of()));
        }

        Claims claims = claimRegions(regions, tickEntityPhase);
        this.lastParallelRegions = claims.regions().size();
        this.lastSkippedRegions = claims.skipped();
        this.lastThrottledRegions = claims.throttled();

        submitClaimedRegions(claims.regions(), drains, blockEntities, entityConsumer,
            tickEntityPhase, levelAccess, chunkWork, eventDrain);
        this.drainDeferredCallbacks();
        DeferredMainThreadWork.drain();
    }

    private Claims claimRegions(List<Region> regions, boolean tickEntityPhase) {
        List<Claimed> claimed = new ArrayList<>(regions.size());
        int skipped = 0;
        int throttled = 0;
        for (Region region : regions) {
            RegionTickState state = this.states.computeIfAbsent(region.id(), RegionTickState::new);
            if (region.tickDivisor() > 1) {
                throttled++;
            }
            if (this.topologyBlocked.contains(region.id()) || !state.tryClaim()) {
                skipped++;
                continue;
            }
            if (tickEntityPhase && state.entityCount() > 0) {
                state.snapshot();
            }
            claimed.add(new Claimed(runFor(region), state));
        }
        return new Claims(claimed, skipped, throttled);
    }

    private void submitClaimedRegions(List<Claimed> claimed, List<ScheduledDrain> drains,
            Map<Integer, List<TickingBlockEntity>> blockEntities,
            Consumer<Entity> entityConsumer, boolean tickEntityPhase,
            RegionalLevelAccess levelAccess, RegionalChunkWork chunkWork, Runnable eventDrain) {
        if (claimed.isEmpty()) {
            if (tickEntityPhase) {
                levelAccess.tessellate$finishRegionalBlockEntities();
            }
            return;
        }

        java.util.concurrent.atomic.AtomicInteger remaining = tickEntityPhase
            ? new java.util.concurrent.atomic.AtomicInteger(claimed.size())
            : null;
        for (Claimed item : claimed) {
            submitClaimedRegion(item, remaining, drains, blockEntities, entityConsumer,
                tickEntityPhase, levelAccess, chunkWork, eventDrain);
        }
    }

    private void submitClaimedRegion(Claimed item,
            java.util.concurrent.atomic.AtomicInteger remaining, List<ScheduledDrain> drains,
            Map<Integer, List<TickingBlockEntity>> blockEntities,
            Consumer<Entity> entityConsumer, boolean tickEntityPhase,
            RegionalLevelAccess levelAccess, RegionalChunkWork chunkWork, Runnable eventDrain) {
        Region region = item.run().region();
        try {
            RegionWorkers.submit(() -> {
                try {
                    tickFullRegion(item.run(), item.state(), drains,
                        blockEntities.getOrDefault(region.id(), List.of()), entityConsumer,
                        tickEntityPhase, levelAccess, chunkWork, eventDrain);
                } finally {
                    item.state().release();
                    DeferredMainThreadWork.deferForRegion(
                        MainThreadBoundaries.Boundary.CHUNK_PLAYER_BROADCASTS,
                        this.levelKey, region.id(),
                        () -> chunkWork.finishRegion().accept(region));
                    finishAsyncBlockEntityPhase(remaining, region, levelAccess);
                }
            }, failure -> {
                RegionTracker.degradeToSerial("region#" + region.id()
                    + " threw on a worker thread: " + failure);
                LOGGER.error("tessellate: region tick failed on a worker thread", failure);
            });
        } catch (RuntimeException failure) {
            item.state().release();
            throw failure;
        }
    }

    private void finishAsyncBlockEntityPhase(java.util.concurrent.atomic.AtomicInteger remaining,
            Region region, RegionalLevelAccess levelAccess) {
        if (remaining != null && remaining.decrementAndGet() == 0) {
            DeferredMainThreadWork.deferForRegion(
                MainThreadBoundaries.Boundary.BLOCK_ENTITY_REGISTRATION,
                this.levelKey, region.id(), levelAccess::tessellate$finishRegionalBlockEntities);
        }
    }

    private RegionRun runFor(Region region) {
        int divisor = Math.max(1, region.tickDivisor());
        int slice = divisor <= 1
            ? -1
            : Math.floorMod(this.serverTick + region.phase(), divisor);
        return new RegionRun(region, divisor, slice);
    }

    private void tickFullRegion(RegionRun run, RegionTickState state,
                                List<ScheduledDrain> drains,
                                List<TickingBlockEntity> blockEntities,
                                Consumer<Entity> entityConsumer, boolean tickEntityPhase,
                                RegionalLevelAccess levelAccess,
                                RegionalChunkWork chunkWork, Runnable eventDrain) {
        Region region = run.region();
        region.recordExecutionThread(Thread.currentThread().getName(),
            RegionWorkers.currentWorkerIndex());
        RegionThreadContext.enter(region, this.levelKey);
        long regionStart = System.nanoTime();
        long nonBlockEntityNanos = 0L;
        try {
            for (ScheduledDrain drain : drains) {
                int budget = Math.max(1, drain.fullBudget() / run.divisor());
                drain.body().accept(budget);
            }
            chunkWork.tickRegion().accept(run);
            if (eventDrain != null) {
                eventDrain.run();
            }

            if (tickEntityPhase) {
                PhaseStats.measure(PhaseStats.Phase.ENTITIES, () -> {
                    for (Entity entity : state.currentSnapshot()) {
                        if (run.includes(entity.getId())) {
                            entityConsumer.accept(entity);
                        }
                    }
                });
                nonBlockEntityNanos = System.nanoTime() - regionStart;
                levelAccess.tessellate$tickRegionalBlockEntities(region, blockEntities);
            }
        } finally {
            region.recordTick(nonBlockEntityNanos == 0L
                ? System.nanoTime() - regionStart
                : nonBlockEntityNanos);
            state.clearSnapshot();
            RegionThreadContext.exit();
        }
    }

    private void runUnownedScheduledDrains(List<ScheduledDrain> drains) {
        UNOWNED_TICK_PASS.set(Boolean.TRUE);
        try {
            for (ScheduledDrain drain : drains) {
                drain.body().accept(drain.fullBudget());
            }
        } finally {
            UNOWNED_TICK_PASS.remove();
        }
    }

    public static boolean isUnownedTickPass() {
        return Boolean.TRUE.equals(UNOWNED_TICK_PASS.get());
    }

    public boolean ownsChunk(long chunkPos) {
        return this.regionizer.regionForChunkLong(chunkPos) != null;
    }

    // One region's entity phase, timed, with the thread bound to the region for the guard.
    //
    // The binding is what lets Ownership.check say which region touched what. It is
    // cleared in a finally block: a worker thread is reused, and a stale binding would make the
    // next region's accesses look like this one's.
    private void tickOneRegion(@Nullable Region region, RegionTickState state, Consumer<Entity> consumer) {
        long regionStart = System.nanoTime();
        int slice = region == null ? -1 : region.sliceOn(this.serverTick);
        if (region != null) {
            region.recordExecutionThread(Thread.currentThread().getName(),
                RegionWorkers.currentWorkerIndex());
            RegionThreadContext.enter(region, this.levelKey);
        }
        try {
            PhaseStats.measure(PhaseStats.Phase.ENTITIES, () -> {
                for (Entity entity : state.currentSnapshot()) {
                    // Sliced by entity id: a stable, evenly spread key, so each entity ticks
                    // exactly once per divisor ticks regardless of list order.
                    if (slice >= 0 && !region.memberInSlice(entity.getId(), slice)) {
                        continue;
                    }
                    consumer.accept(entity);
                }
            });
        } finally {
            if (region != null) {
                RegionThreadContext.exit();
                region.recordTick(System.nanoTime() - regionStart);
            }
            state.clearSnapshot();
        }
    }

    // Runs body once per region, with the calling thread bound to that region.
    //
    // Used for scheduled block and fluid ticks, which already filter by chunk position.
    // Scoping runs the same drain once per region and
    // letting the predicate reject positions the bound region does not own. A final unbound pass
    // catches anything belonging to no region, so nothing can be starved by a gap in the map.
    //
    // Always sequential. These subsystems still share level-global structures, so they are not
    // candidates for the worker pool until those are split too.
    public void runPerRegionThenUnbound(java.util.function.IntConsumer body, int fullBudget) {
        for (Region region : this.regionizer.regions()) {
            // A throttled region gets a proportionally smaller drain budget rather than being
            // skipped outright. LevelTicks leaves whatever it cannot fit queued and retries it,
            // so the region drains at a reduced steady rate instead of in bursts.
            int budget = Math.max(1, fullBudget / Math.max(1, region.tickDivisor()));
            RegionThreadContext.enter(region, this.levelKey);
            try {
                body.accept(budget);
            } finally {
                RegionThreadContext.exit();
            }
        }
        // Unbound: positions owned by no region.
        UNOWNED_TICK_PASS.set(Boolean.TRUE);
        try {
            body.accept(fullBudget);
        } finally {
            UNOWNED_TICK_PASS.remove();
        }
    }

    // A natural-spawn hook can trip the global guard before the full region envelope runs.
    // Preserve the scheduled work already captured earlier in this tick before serial fallback.
    public void drainCapturedScheduledFallback() {
        for (ScheduledDrain drain : List.copyOf(this.scheduledDrains)) {
            runPerRegionThenUnbound(drain.body(), drain.fullBudget());
        }
        this.scheduledDrains.clear();
    }

    public void runPerRegionThenUnbound(Runnable body) {
        for (Region region : this.regionizer.regions()) {
            RegionThreadContext.enter(region, this.levelKey);
            try {
                body.run();
            } finally {
                RegionThreadContext.exit();
            }
        }
        body.run();
    }

    // True when the calling thread is bound to a region that owns this chunk.
    public static boolean boundRegionOwns(long chunkPos) {
        RegionThreadContext.Binding binding = RegionThreadContext.currentBinding();
        return binding != null && binding.region().ownsChunkLong(chunkPos);
    }

    public static boolean hasBoundRegion() {
        return RegionThreadContext.currentBinding() != null;
    }

    // Regions that did no work this tick because they are throttled.
    public int lastThrottledRegions() {
        return this.lastThrottledRegions;
    }

    public int lastParallelRegions() {
        return this.lastParallelRegions;
    }

    // Regions that were still busy from an earlier tick and were therefore skipped.
    public int lastSkippedRegions() {
        return this.lastSkippedRegions;
    }

    // A chunk crossed the entity-ticking threshold. Main thread, from ChunkMapMixin.
    //
    // Only queues. The queue is applied in tick() so that region identity is stable
    // for the whole of a tick.
    public void onChunkStatusChange(long chunkPos, boolean entityTicking) {
        if (entityTicking) {
            if (this.tickingChunks.add(chunkPos)) {
                blockTopologyAround(chunkPos);
                this.regionizer.queueAddChunkLong(chunkPos);
            }
        } else if (this.tickingChunks.contains(chunkPos)) {
            // Finish the current owner before unload can inspect or snapshot the chunk, then block
            // the region so the next tick cannot reclaim it before queued removal is applied.
            this.leaseChunk(chunkPos);
            if (this.tickingChunks.remove(chunkPos)) {
                blockTopologyAround(chunkPos);
                this.regionizer.queueRemoveChunkLong(chunkPos);
            }
        }
    }

    // Main thread, at the start of the level tick, before any ticking subsystem runs.
    public void tick() {
        resetTickState();

        boolean initialVerify = !this.verified;
        boolean verifyNow = shouldVerify(initialVerify);
        prepareVerification(verifyNow, initialVerify);
        applyPendingTopology();

        this.blockEvents.reconcile();

        // Membership must be checked against the topology the scan just produced. Checking it
        // inside verify() briefly classified valid entities as orphans until update() ran.
        if (verifyNow) {
            verifyEntities();
        }

        updateRegionCosts();
        RegionThrottle.apply(this.regionizer.regions());
    }

    private void resetTickState() {
        // Cleared here and set again only if the region entity path actually runs, so that a
        // stale value cannot make the vanilla path look like the region path.
        this.lastEntityTickNanos = 0L;
        this.scheduledDrains.clear();
        this.blockEventDrain = null;
        this.fullRegionTickExecuted = false;
        this.serverTick++;
    }

    private boolean shouldVerify(boolean initialVerify) {
        return initialVerify || Config.verifyIntervalTicks > 0
            && ++this.ticksSinceVerify >= Config.verifyIntervalTicks;
    }

    private void prepareVerification(boolean verifyNow, boolean initialVerify) {
        // A repair pass can discover arbitrary drift, so it remains the rare global quiescence
        // boundary. Incremental topology changes already blocked and leased only their neighbors.
        if (verifyNow && RegionWorkers.anyTaskInFlight()) {
            RegionWorkers.awaitIdle();
        }

        if (!RegionTracker.parallelAllowed()) {
            awaitAllRegions();
        }

        if (verifyNow) {
            this.ticksSinceVerify = 0;
            verify(initialVerify);
        }
    }

    private void applyPendingTopology() {
        if (this.regionizer.hasPendingChanges()) {
            MainThreadBoundaries.measure(MainThreadBoundaries.Boundary.TOPOLOGY_BARRIER,
                MainThreadBoundaries.globalSource(this.levelKey), () -> {
                    awaitBlockedTopology();
                    // The wait can make callbacks and handoffs ready. Replay them against the
                    // old ownership map before changing that map.
                    drainDeferredCallbacks();
                    DeferredMainThreadWork.drain();
                    long start = System.nanoTime();
                    this.regionizer.update();
                    this.topologyVersion++;
                    rehomeOrphans();
                    this.topologyBlocked.clear();
                    this.lastUpdateNanos = System.nanoTime() - start;
                });
        } else {
            this.lastUpdateNanos = 0L;
            // An add/remove pair can cancel in Regionizer's queue during one server tick. The
            // affected regions were already leased, and with no net topology work they may run.
            this.topologyBlocked.clear();
        }
    }

    private void updateRegionCosts() {
        // Refresh each region's cost estimate from the last tick it actually ran, then decide who
        // runs on this one. Done here, before any subsystem, so entities, block entities and
        // scheduled ticks all act on the same decision.
        for (Region region : this.regionizer.regions()) {
            // lastTickNanos measures only the slice that ran, so scale back up to a
            // whole-region estimate or the throttle would immediately undo itself.
            region.updateCost(
                region.lastTickNanos() * region.tickDivisor(),
                region.lastBlockEntityNanos() * region.tickDivisor());
        }
    }

    // Whether this region runs on the current tick, honouring its throttle.
    //
    // A skipped region skips entities, block entities, and scheduled ticks, so it is
    // simply slower rather than internally inconsistent.
    // Waits for whichever region owns this cell to stop ticking.
    //
    // The lease that replaces a global barrier. Main-thread code calls this before touching
    // region-owned state; it waits for one region only, so a slow region stalls the operations
    // that target it and nothing else.
    public void leaseCell(long cell) {
        Region region = this.regionizer.regionForSection(cell);
        if (region == null) {
            return;
        }
        RegionTickState state = this.states.get(region.id());
        if (state != null) {
            state.awaitIdle();
        }
    }

    public void leaseChunk(long chunkPos) {
        this.leaseCell(RegionSectionPos.fromChunkLong(chunkPos, this.regionizer.sectionShift()));
    }

    public boolean regionIdle(int regionId) {
        RegionTickState state = this.states.get(regionId);
        return state == null || !state.isInFlight();
    }

    public boolean chunkIdle(long chunkPos) {
        Region region = this.regionizer.regionForChunkLong(chunkPos);
        return region == null || regionIdle(region.id());
    }

    public void awaitAllRegions() {
        for (RegionTickState state : this.states.values()) {
            state.awaitIdle();
        }
    }

    private void blockTopologyAround(long chunkPos) {
        // The topology mutation is only queued here. Waiting while a caller holds a vanilla
        // subsystem lock can invert that lock with a running region. The next tick waits in
        // awaitBlockedTopology before applying the queued change.
        long cell = RegionSectionPos.fromChunkLong(chunkPos, this.regionizer.sectionShift());
        int x = RegionSectionPos.x(cell);
        int z = RegionSectionPos.z(cell);
        int radius = this.regionizer.mergeRadius();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Region region = this.regionizer.regionForSection(
                    RegionSectionPos.pack(x + dx, z + dz));
                if (region != null) {
                    this.topologyBlocked.add(region.id());
                }
            }
        }
    }

    private void awaitBlockedTopology() {
        for (int regionId : this.topologyBlocked) {
            RegionTickState state = this.states.get(regionId);
            if (state != null) {
                state.awaitIdle();
            }
        }
    }

    // Reports, rather than prevents, the main thread touching a region that is still ticking.
    //
    // Detection first: every such site is a place that will need a lease before parallel
    // ticking can be trusted, and the reliable way to find them all is to let the server say so.
    public void assertCellNotInFlight(long cell) {
        if (!RegionThreadContext.onMainThread()) {
            return;
        }
        Region region = this.regionizer.regionForSection(cell);
        if (region == null) {
            return;
        }
        RegionTickState state = this.states.get(region.id());
        if (state != null && state.isInFlight()) {
            Ownership.reportMainThreadRace(this.levelKey, region.id(), cell,
                RegionTracker.violations(), Config.strictGuard);
        }
    }

    public DeferredEntityCallbacks deferredCallbacks() {
        return this.deferredCallbacks;
    }

    // Replays everything a worker queued. Main thread only.
    //
    // Called both at the start of the level tick, before anything reads what these callbacks
    // update, and immediately after the entity phase, which with a barrier drains them in the same
    // tick they were raised.
    public void drainDeferredCallbacks() {
        if (this.deferredCallbacks.isEmpty()) {
            return;
        }
        if (this.levelCallbacks == null) {
            this.levelCallbacks = EntityStorageBinding.callbacksOf(this.level);
        }
        if (this.levelCallbacks != null) {
            this.deferredCallbacks.drain(this.levelCallbacks, this::callbackReady);
        }
    }

    private boolean callbackReady(Entity entity) {
        RegionTickState owner = findOwnerWithoutPosition(entity);
        if (owner != null && owner.isInFlight()) {
            return false;
        }
        Region region = this.regionizer.regionForChunkLong(entity.chunkPosition().toLong());
        return region == null || regionIdle(region.id());
    }

    @Nullable
    private RegionTickState findOwnerWithoutPosition(Entity entity) {
        if (this.orphans.containsEntity(entity)) {
            return this.orphans;
        }
        for (RegionTickState state : this.states.values()) {
            if (state.containsEntity(entity)) {
                return state;
            }
        }
        return null;
    }

    public long serverTick() {
        return this.serverTick;
    }

    // Gives orphaned entities a region once one exists for their chunk.
    //
    // An entity starts ticking the instant its chunk becomes entity-ticking, but regionizer
    // changes are queued and only applied here. An entity entering a chunk whose region has not
    // been created yet therefore lands in the orphan bucket, and without this would stay there
    // until the next verify pass. It still ticks but is attributed to no region.
    //
    // Runs only when the regionizer actually changed something, and the bucket is empty in the
    // steady state, so this costs nothing on a normal tick.
    private void rehomeOrphans() {
        if (this.orphans.entityCount() == 0) {
            return;
        }
        List<Entity> pending = new ArrayList<>(this.orphans.snapshot());
        this.orphans.clearSnapshot();
        for (Entity entity : pending) {
            RegionTickState target = stateForEntity(entity);
            if (target != this.orphans) {
                this.orphans.removeEntity(entity);
                target.addEntity(entity);
            }
        }
    }

    // Full rescan, reconciling the incremental view against the chunk map.
    //
    // Any difference is a bug in the incremental path, so it is repaired and counted rather
    // than ignored.
    private void verify(boolean initialVerify) {
        long start = System.nanoTime();

        this.scratch.clear();
        EntityStorageBinding.copyTickingChunks(this.level, this.scratch);

        int drift = 0;
        for (LongIterator it = this.scratch.iterator(); it.hasNext(); ) {
            long pos = it.nextLong();
            if (!this.tickingChunks.contains(pos)) {
                this.regionizer.queueAddChunkLong(pos);
                drift++;
            }
        }
        for (LongIterator it = this.tickingChunks.iterator(); it.hasNext(); ) {
            long pos = it.nextLong();
            if (!this.scratch.contains(pos)) {
                this.regionizer.queueRemoveChunkLong(pos);
                drift++;
            }
        }

        if (drift > 0) {
            if (!initialVerify) {
                this.discrepancies += drift;
                LOGGER.warn(
                    "[{}] region index drifted from the chunk map by {} chunk(s); repaired. "
                        + "This means an entity-ticking transition was missed.",
                    this.levelKey, drift);
            }
            this.tickingChunks.clear();
            this.tickingChunks.addAll(this.scratch);
        }

        this.lastVerifyNanos = System.nanoTime() - start;
        this.verified = true;
        if (this.lastVerifyNanos > this.peakVerifyNanos) {
            this.peakVerifyNanos = this.lastVerifyNanos;
        }
    }

    // Groups the level's block-entity tickers by region and returns an iterator over them.
    //
    // Handed to vanilla's tickBlockEntities loop in place of the flat list iterator, so
    // every check and call in that loop stays vanilla's; only the order changes.
    //
    // Membership is rebuilt from the level's list each tick rather than tracked incrementally.
    // The level list stays the single source of truth, so debug dumps work and no copy can drift.
    // Block entities never move, so the grouping is a position
    // lookup. Removed tickers are dropped up front, which is what vanilla's loop would have done
    // one at a time.
    public void prepareBlockEntityTickers(List<TickingBlockEntity> tickers) {
        long start = System.nanoTime();

        // Compact, dropping the remembered position along with the ticker so the cache stays
        // bounded by the number of live tickers.
        Iterator<TickingBlockEntity> compacting = tickers.iterator();
        while (compacting.hasNext()) {
            TickingBlockEntity ticker = compacting.next();
            if (ticker.isRemoved()) {
                this.tickerPositions.remove(ticker);
                compacting.remove();
            }
        }

        for (RegionTickState state : this.states.values()) {
            state.clearBlockEntityBuffer();
        }
        this.orphans.clearBlockEntityBuffer();

        for (TickingBlockEntity ticker : tickers) {
            BlockPos pos = positionOf(ticker);
            if (pos == null) {
                // A ticker we cannot place must never take the server down: the compat contract
                // is best-effort, and a third-party ticker is free to behave unexpectedly here.
                // It goes to the orphan bucket, which vanilla's loop still ticks, in order.
                reportUnplaceableTicker(ticker);
                this.orphans.bufferBlockEntity(ticker);
                continue;
            }
            stateForPos(pos).bufferBlockEntity(ticker);
        }

        this.lastBlockEntityGroupNanos = System.nanoTime() - start;
        this.orphanBlockEntityCount = this.orphans.blockEntityCount();
    }

    // Copies block-entity membership so an async task never reads next tick's reused buffers.
    public Map<Integer, List<TickingBlockEntity>> snapshotBlockEntityTickers(
        List<TickingBlockEntity> tickers) {
        long start = System.nanoTime();
        tickers.removeIf(this::removeTickerIfRemoved);

        Map<Integer, List<TickingBlockEntity>> grouped = new HashMap<>();
        for (RegionTickState state : this.states.values()) {
            state.reportBlockEntityCount(0);
        }
        int orphanCount = groupBlockEntityTickers(tickers, grouped);
        grouped.replaceAll((ignored, values) -> List.copyOf(values));
        reportBlockEntityCounts(grouped);
        this.lastBlockEntityGroupNanos = System.nanoTime() - start;
        this.orphanBlockEntityCount = orphanCount;
        return Map.copyOf(grouped);
    }

    private boolean removeTickerIfRemoved(TickingBlockEntity ticker) {
        if (!ticker.isRemoved()) {
            return false;
        }
        this.tickerPositions.remove(ticker);
        return true;
    }

    private int groupBlockEntityTickers(List<TickingBlockEntity> tickers,
            Map<Integer, List<TickingBlockEntity>> grouped) {
        int orphanCount = 0;
        for (TickingBlockEntity ticker : tickers) {
            BlockPos pos = positionOf(ticker);
            Region region = pos == null ? null : this.regionizer.regionForChunk(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
            if (region == null) {
                if (pos == null) {
                    reportUnplaceableTicker(ticker);
                }
                grouped.computeIfAbsent(-1, ignored -> new ArrayList<>()).add(ticker);
                orphanCount++;
                continue;
            }
            RegionRun run = runFor(region);
            if (run.includes(pos.hashCode())) {
                grouped.computeIfAbsent(region.id(), ignored -> new ArrayList<>()).add(ticker);
            }
        }
        return orphanCount;
    }

    private void reportBlockEntityCounts(Map<Integer, List<TickingBlockEntity>> grouped) {
        for (Map.Entry<Integer, List<TickingBlockEntity>> entry : grouped.entrySet()) {
            if (entry.getKey() >= 0) {
                this.states.computeIfAbsent(entry.getKey(), RegionTickState::new)
                    .reportBlockEntityCount(entry.getValue().size());
            }
        }
    }

    public Iterator<TickingBlockEntity> orderBlockEntityTickers(List<TickingBlockEntity> tickers) {
        prepareBlockEntityTickers(tickers);

        List<Region> regionOrder = new ArrayList<>(this.states.size() + 1);
        List<List<TickingBlockEntity>> buffers = new ArrayList<>(this.states.size() + 1);
        for (Region region : this.regionizer.regions()) {
            RegionTickState state = this.states.get(region.id());
            if (state == null) {
                continue;
            }
            regionOrder.add(region);
            int slice = region.sliceOn(this.serverTick);
            // Sliced by position hash, the block-entity equivalent of slicing entities by id.
            buffers.add(slice < 0 ? state.blockEntityBuffer() : state.sliceBlockEntities(region, slice));
        }
        // Orphans last, with no region to attribute them to.
        regionOrder.add(null);
        buffers.add(this.orphans.blockEntityBuffer());

        return new RegionOrderedTickers(tickers, regionOrder, buffers);
    }

    // A ticker's block position, remembered from the last tick it was willing to report one.
    //
    // Lithium's block_entity_ticking.sleeping feature rebinds a sleeping ticker to a
    // delegate whose getPos() returns null. Lithium's own shouldTickBlocksAt
    // redirect tolerates that, so it is deliberate rather than a bug. Block entities never move,
    // so the position seen while the ticker was awake stays valid for as long as it lives.
    @Nullable
    private BlockPos positionOf(TickingBlockEntity ticker) {
        BlockPos pos = ticker.getPos();
        if (pos != null) {
            this.tickerPositions.putIfAbsent(ticker, pos);
            return pos;
        }
        return this.tickerPositions.get(ticker);
    }

    // Logged once per ticker implementation, since this would otherwise repeat every tick.
    private void reportUnplaceableTicker(TickingBlockEntity ticker) {
        String type = ticker.getClass().getName();
        if (this.unplaceableTickerTypes.add(type)) {
            LOGGER.warn("[{}] block entity ticker {} reports a null position "
                    + "(type '{}', removed={}); it cannot be assigned to a region and will be "
                    + "ticked last. Regional attribution for it is unavailable.",
                this.levelKey, type, safeType(ticker), ticker.isRemoved());
        }
    }

    private static String safeType(TickingBlockEntity ticker) {
        try {
            return ticker.getType();
        } catch (RuntimeException e) {
            return "<threw " + e.getClass().getSimpleName() + ">";
        }
    }

    // The state whose region owns this position's chunk, or the orphan bucket.
    private RegionTickState stateForPos(BlockPos pos) {
        Region region = this.regionizer.regionForChunk(
            SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        if (region == null) {
            return this.orphans;
        }
        return this.states.computeIfAbsent(region.id(), RegionTickState::new);
    }

    public long lastBlockEntityGroupNanos() {
        return this.lastBlockEntityGroupNanos;
    }

    public int orphanBlockEntityCount() {
        return this.orphanBlockEntityCount;
    }

    // Reconciles per-region entity membership against each entity's actual position.
    //
    // Incremental maintenance through the lifecycle callbacks is the fast path; this is the
    // cross-check that turns a missed callback into a logged, repaired discrepancy instead of an
    // entity that ticks in the wrong region or twice.
    private void verifyEntities() {
        int misplaced = 0;

        List<Entity> all = new ArrayList<>();
        for (RegionTickState state : this.states.values()) {
            all.addAll(state.snapshot());
            state.clearSnapshot();
        }
        all.addAll(this.orphans.snapshot());
        this.orphans.clearSnapshot();

        for (Entity entity : all) {
            RegionTickState expected = stateForEntity(entity);
            RegionTickState owner = findOwner(entity);
            if (owner != expected) {
                if (owner != null) {
                    owner.removeEntity(entity);
                }
                expected.addEntity(entity);
                misplaced++;
            }
        }

        if (misplaced > 0) {
            this.entityDiscrepancies += misplaced;
            LOGGER.warn("[{}] {} ticking entity(s) were in the wrong region; repaired. "
                    + "This means an entity lifecycle or section-change callback was missed.",
                this.levelKey, misplaced);
        }
        if (this.orphans.entityCount() > 0) {
            LOGGER.warn("[{}] {} ticking entity(s) belong to no region; they are ticked last",
                this.levelKey, this.orphans.entityCount());
        }
    }

    // Which region owns a chunk, or null if the chunk is not entity-ticking.
    @Nullable
    public Region regionForChunk(ChunkPos pos) {
        return this.regionizer.regionForChunkLong(pos.toLong());
    }

    // ---- RegionizerListener ----------------------------------------------------------------

    @Override
    public void onRegionCreated(Region region) {
        this.states.computeIfAbsent(region.id(), RegionTickState::new);
        if (Config.logRegionChanges) {
            LOGGER.info("[{}] region#{} created with {} section(s)",
                this.levelKey, region.id(), region.sectionCount());
        }
    }

    // The survivor takes over every absorbed region's entities.
    @Override
    public void onRegionsMerged(Region survivor, List<Region> merged) {
        RegionTickState target = this.states.computeIfAbsent(survivor.id(), RegionTickState::new);
        for (Region dead : merged) {
            RegionTickState state = this.states.get(dead.id());
            if (state != null) {
                target.absorb(state);
            }
        }
        if (Config.logRegionChanges) {
            LOGGER.info("[{}] region#{} absorbed {} region(s)", this.levelKey, survivor.id(), merged.size());
        }
    }

    // Entities in the original region may now belong to one of the new ones, so the original's
    // membership is re-evaluated against the regionizer.
    @Override
    public void onRegionSplit(Region original, List<Region> splitOff) {
        for (Region fresh : splitOff) {
            this.states.computeIfAbsent(fresh.id(), RegionTickState::new);
        }

        RegionTickState source = this.states.get(original.id());
        if (source != null && source.entityCount() > 0) {
            List<Entity> members = new ArrayList<>(source.snapshot());
            source.clearSnapshot();
            for (Entity entity : members) {
                RegionTickState target = stateForEntity(entity);
                if (target != source) {
                    source.removeEntity(entity);
                    target.addEntity(entity);
                }
            }
        }

        if (Config.logRegionChanges) {
            LOGGER.info("[{}] region#{} split off {} region(s)", this.levelKey, original.id(), splitOff.size());
        }
    }

    // A destroyed region's entities move to the orphan bucket rather than being dropped.
    //
    // Normally the region is already empty, because chunks stop entity-ticking before they
    // unload and each entity leaves through onTickingEnd. Anything still here would
    // otherwise silently stop ticking.
    @Override
    public void onRegionDestroyed(Region region) {
        RegionTickState state = this.states.remove(region.id());
        if (state != null && state.entityCount() > 0) {
            this.orphans.absorb(state);
            LOGGER.warn("[{}] region#{} was destroyed holding {} ticking entity(s); "
                    + "moved to the orphan bucket so they keep ticking",
                this.levelKey, region.id(), this.orphans.entityCount());
        }
        if (Config.logRegionChanges) {
            LOGGER.info("[{}] region#{} destroyed", this.levelKey, region.id());
        }
    }
}

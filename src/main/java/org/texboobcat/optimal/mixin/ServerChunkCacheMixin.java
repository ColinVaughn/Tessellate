package org.texboobcat.optimal.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.texboobcat.optimal.Config;
import org.texboobcat.optimal.guard.RegionThreadContext;
import org.texboobcat.optimal.region.DeferredMainThreadWork;
import org.texboobcat.optimal.region.LevelRegionIndex;
import org.texboobcat.optimal.region.MainThreadBoundaries;
import org.texboobcat.optimal.region.ParallelNaturalSpawner;
import org.texboobcat.optimal.region.PhaseStats;
import org.texboobcat.optimal.region.Region;
import org.texboobcat.optimal.region.RegionTracker;
import org.texboobcat.optimal.region.RegionWorkers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import javax.annotation.Nullable;

// Regional chunk/spawn ticking plus non-blocking chunk reads for region worker threads.
//
// Vanilla's getChunk sends any off-main-thread request to mainThreadProcessor
// and blocks on the result. That is fatal here twice over: every region worker would serialize
// through the main thread, destroying the parallelism, and while the main thread is waiting on a
// region barrier there is nobody left to drain that queue, so the whole server deadlocks.
//
// Workers therefore read straight from ChunkMap.visibleChunkMap, which is a volatile
// field replaced wholesale rather than mutated, so another thread always observes a consistent
// snapshot. getChunkIfPresent returns what is already loaded without ever scheduling work.
//
// If the chunk is not already available the worker cannot obtain it without blocking, so it
// gives up rather than deadlocking: the miss is recorded and the coordinator falls back to serial
// ticking from the next tick. The compatibility contract preserves correctness by retreating to
// the single-threaded path instead of risking a hung server.
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin implements LevelRegionIndex.RegionalChunkAccess {

    @Unique
    private static final Logger OPTIMAL$LOGGER = LogUtils.getLogger();

    @Shadow
    @Final
    public ChunkMap chunkMap;

    @Shadow
    @Final
    public ServerLevel level;

    @Shadow
    @Final
    private DistanceManager distanceManager;

    @Shadow
    private long lastInhabitedUpdate;

    @Shadow
    private boolean spawnEnemies;

    @Shadow
    private boolean spawnFriendlies;

    @Shadow
    @Nullable
    private NaturalSpawner.SpawnState lastSpawnState;

    @Unique
    private LevelRegionIndex.RegionalChunkWork optimal$regionalChunkWork =
        new LevelRegionIndex.RegionalChunkWork(region -> { }, () -> { },
            region -> { }, () -> { });

    @Inject(method = "tickChunks", at = @At("HEAD"), cancellable = true)
    private void optimal$replaceWithRegionalChunkTick(CallbackInfo ci) {
        LevelRegionIndex index = RegionTracker.index(this.level);
        if (index == null || !index.usesFullRegionTick()) {
            return;
        }
        optimal$prepareRegionalChunks(index);
        ci.cancel();
    }

    @Override
    public void optimal$prepareRegionalChunks(LevelRegionIndex index) {
        long gameTime = this.level.getGameTime();
        long inhabitedDelta = gameTime - this.lastInhabitedUpdate;
        this.lastInhabitedUpdate = gameTime;

        boolean chunksRunNormally = !this.level.isDebug()
            && this.level.tickRateManager().runsNormally();
        Map<Integer, List<LevelChunk>> regional = new HashMap<>();
        List<LevelChunk> unowned = new ArrayList<>();
        Set<Long> eligibleChunks = new HashSet<>();
        if (!this.level.isDebug()) {
            optimal$collectTickingChunks(index, regional, unowned, eligibleChunks,
                chunksRunNormally);
        }
        int randomTickSpeed = this.level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        boolean periodicSpawnTick = this.level.getLevelData().getGameTime() % 400L == 0L;

        NaturalSpawner.SpawnState spawnState = null;
        boolean parallelSpawnAttempted = false;
        if (chunksRunNormally) {
            List<Entity> entities = new ArrayList<>();
            this.level.getAllEntities().forEach(entities::add);
            int spawnableChunks = this.distanceManager.getNaturalSpawnChunkCount();
            spawnState = NaturalSpawner.createState(spawnableChunks, entities,
                this::optimal$getFullChunk, new LocalMobCapCalculator(this.chunkMap));
            this.lastSpawnState = spawnState;

            if (optimal$canRunParallelSpawning(spawnableChunks)) {
                parallelSpawnAttempted = true;
                ParallelNaturalSpawner.State parallelState = optimal$newParallelSpawnState(
                    spawnableChunks, spawnState, entities);
                if (parallelState == null) {
                    optimal$finishChunkFallback(regional, unowned, index, spawnState,
                        eligibleChunks, inhabitedDelta, randomTickSpeed, periodicSpawnTick, true);
                    return;
                }
                if (!optimal$runParallelNaturalSpawning(index, regional, unowned, spawnState,
                    parallelState, eligibleChunks, periodicSpawnTick)) {
                    optimal$finishChunkFallback(regional, unowned, index, spawnState,
                        eligibleChunks, inhabitedDelta, randomTickSpeed, periodicSpawnTick, false);
                    return;
                }
            }
        }

        NaturalSpawner.SpawnState capturedSpawnState = spawnState;
        boolean spawnInChunkWork = !parallelSpawnAttempted;
        this.optimal$regionalChunkWork = new LevelRegionIndex.RegionalChunkWork(
            run -> optimal$tickChunkList(
                regional.getOrDefault(run.region().id(), List.of()), run, capturedSpawnState,
                eligibleChunks, inhabitedDelta, chunksRunNormally, randomTickSpeed,
                periodicSpawnTick,
                spawnInChunkWork),
            () -> optimal$tickChunkList(unowned, null, capturedSpawnState,
                eligibleChunks, inhabitedDelta, chunksRunNormally, randomTickSpeed,
                periodicSpawnTick,
                spawnInChunkWork),
            region -> optimal$broadcast(regional.getOrDefault(region.id(), List.of()),
                MainThreadBoundaries.source(index.levelKey(), region.id())),
            () -> optimal$finishGlobalChunkWork(index, unowned, chunksRunNormally));
    }

    @Unique
    private void optimal$collectTickingChunks(LevelRegionIndex index,
            Map<Integer, List<LevelChunk>> regional, List<LevelChunk> unowned,
            Set<Long> eligibleChunks, boolean chunksRunNormally) {
        for (ChunkHolder holder : this.chunkMap.getChunks()) {
            LevelChunk chunk = holder.getTickingChunk();
            if (chunk == null) {
                continue;
            }
            Region region = index.regionForChunk(chunk.getPos());
            if (region == null) {
                unowned.add(chunk);
            } else {
                regional.computeIfAbsent(region.id(), ignored -> new ArrayList<>()).add(chunk);
            }
            ChunkPos pos = chunk.getPos();
            if (chunksRunNormally && optimal$isSpawnEligible(pos)) {
                eligibleChunks.add(pos.toLong());
            }
        }
    }

    @Unique
    private boolean optimal$isSpawnEligible(ChunkPos pos) {
        return this.level.isNaturalSpawningAllowed(pos)
            && ((ChunkMapInvoker) this.chunkMap).optimal$anyPlayerCloseEnoughForSpawning(pos)
            || this.distanceManager.shouldForceTicks(pos.toLong());
    }

    @Unique
    private boolean optimal$canRunParallelSpawning(int spawnableChunks) {
        return Config.parallelNaturalSpawning && ParallelNaturalSpawner.parallelAllowed()
            && this.level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)
            && spawnableChunks > 0 && (this.spawnEnemies || this.spawnFriendlies);
    }

    @Unique
    @Nullable
    private ParallelNaturalSpawner.State optimal$newParallelSpawnState(int spawnableChunks,
            NaturalSpawner.SpawnState spawnState, List<Entity> entities) {
        try {
            return new ParallelNaturalSpawner.State(this.level, spawnableChunks,
                spawnState.getMobCategoryCounts(), entities, this::optimal$getFullChunk);
        } catch (Throwable failure) {
            ParallelNaturalSpawner.failed();
            ParallelNaturalSpawner.degradeToSerial("natural-spawn snapshot failed: " + failure);
            OPTIMAL$LOGGER.error("optimal: natural-spawn snapshot failed", failure);
            return null;
        }
    }

    @Unique
    private void optimal$finishGlobalChunkWork(LevelRegionIndex index, List<LevelChunk> unowned,
                                                boolean chunksRunNormally) {
        if (chunksRunNormally
            && this.level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
            MainThreadBoundaries.measure(MainThreadBoundaries.Boundary.CUSTOM_SPAWNERS,
                MainThreadBoundaries.globalSource(index.levelKey()), () -> {
                    for (net.minecraft.server.level.ServerPlayer player : this.level.players()) {
                        index.leaseChunk(player.chunkPosition().toLong());
                    }
                    this.level.tickCustomSpawners(this.spawnEnemies, this.spawnFriendlies);
                });
        }
        optimal$broadcast(unowned, MainThreadBoundaries.source(index.levelKey(), -1));
    }

    @Override
    public LevelRegionIndex.RegionalChunkWork optimal$regionalChunkWork() {
        return this.optimal$regionalChunkWork;
    }

    @Unique
    private void optimal$tickChunkList(List<LevelChunk> source,
                                        LevelRegionIndex.RegionRun run,
                                        NaturalSpawner.SpawnState spawnState,
                                        Set<Long> eligibleChunks, long inhabitedDelta,
                                        boolean chunksRunNormally, int randomTickSpeed,
                                        boolean periodicSpawnTick, boolean spawnMobs) {
        if (!chunksRunNormally || source.isEmpty()) {
            return;
        }

        List<LevelChunk> chunks = optimal$filterTickChunks(source, run);
        Util.shuffle(chunks, this.level.random);

        boolean mobSpawning = this.level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);

        for (LevelChunk chunk : chunks) {
            optimal$tickChunk(chunk, spawnState, eligibleChunks, inhabitedDelta, randomTickSpeed,
                periodicSpawnTick, spawnMobs, mobSpawning);
        }
    }

    @Unique
    private List<LevelChunk> optimal$filterTickChunks(List<LevelChunk> source,
                                                       @Nullable LevelRegionIndex.RegionRun run) {
        List<LevelChunk> chunks = new ArrayList<>(source.size());
        for (LevelChunk chunk : source) {
            if (run == null || run.includes(Long.hashCode(chunk.getPos().toLong()))) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    @Unique
    private void optimal$tickChunk(LevelChunk chunk, NaturalSpawner.SpawnState spawnState,
            Set<Long> eligibleChunks, long inhabitedDelta, int randomTickSpeed,
            boolean periodicSpawnTick, boolean spawnMobs, boolean mobSpawning) {
        ChunkPos pos = chunk.getPos();
        if (!eligibleChunks.contains(pos.toLong())) {
            return;
        }
        chunk.incrementInhabitedTime(inhabitedDelta);
        optimal$spawnMobs(chunk, pos, spawnState, periodicSpawnTick, spawnMobs, mobSpawning);
        if (this.level.shouldTickBlocksAt(pos.toLong())) {
            PhaseStats.measure(PhaseStats.Phase.CHUNK_TICKS,
                () -> this.level.tickChunk(chunk, randomTickSpeed));
        }
    }

    @Unique
    private void optimal$spawnMobs(LevelChunk chunk, ChunkPos pos,
            NaturalSpawner.SpawnState spawnState, boolean periodicSpawnTick,
            boolean spawnMobs, boolean mobSpawning) {
        if (!spawnMobs || !mobSpawning || spawnState == null
            || !(this.spawnEnemies || this.spawnFriendlies)
            || !this.level.getWorldBorder().isWithinBounds(pos)) {
            return;
        }
        if (RegionWorkers.isWorkerThread()) {
            DeferredMainThreadWork.defer(MainThreadBoundaries.Boundary.NATURAL_SPAWN_COMMITS,
                () -> optimal$spawnForChunk(chunk, spawnState, periodicSpawnTick));
        } else {
            optimal$spawnForChunk(chunk, spawnState, periodicSpawnTick);
        }
    }

    @Unique
    private boolean optimal$runParallelNaturalSpawning(LevelRegionIndex index,
            Map<Integer, List<LevelChunk>> regional, List<LevelChunk> unowned,
            NaturalSpawner.SpawnState spawnState, ParallelNaturalSpawner.State parallelState,
            Set<Long> eligibleChunks, boolean periodicSpawnTick) {
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Runnable> tasks = new ArrayList<>();
        optimal$addSpawnTasks(index, regional, spawnState, parallelState, eligibleChunks,
            periodicSpawnTick, failures, tasks);

        optimal$spawnUnowned(unowned, spawnState, parallelState, eligibleChunks,
            periodicSpawnTick, failures);
        RegionWorkers.runAllAndWait(tasks);
        DeferredMainThreadWork.drain();

        return optimal$parallelSpawningSucceeded(failures);
    }

    @Unique
    private void optimal$addSpawnTasks(LevelRegionIndex index,
            Map<Integer, List<LevelChunk>> regional, NaturalSpawner.SpawnState spawnState,
            ParallelNaturalSpawner.State parallelState, Set<Long> eligibleChunks,
            boolean periodicSpawnTick, Queue<Throwable> failures, List<Runnable> tasks) {
        for (Region region : index.regionizer().regions()) {
            if (!index.regionIdle(region.id())) {
                continue;
            }
            int slice = region.sliceOn(index.serverTick());
            List<LevelChunk> chunks = optimal$spawnChunks(
                regional.getOrDefault(region.id(), List.of()), region, slice, eligibleChunks);
            if (chunks.isEmpty()) {
                continue;
            }
            tasks.add(optimal$spawnTask(region, index.levelKey(), chunks, spawnState,
                parallelState, periodicSpawnTick, failures));
        }
    }

    @Unique
    private Runnable optimal$spawnTask(Region region, String levelKey, List<LevelChunk> chunks,
            NaturalSpawner.SpawnState spawnState, ParallelNaturalSpawner.State parallelState,
            boolean periodicSpawnTick, Queue<Throwable> failures) {
        return () -> {
            RegionThreadContext.enter(region, levelKey);
            try {
                for (LevelChunk chunk : chunks) {
                    optimal$spawnForChunkParallel(chunk, spawnState, parallelState,
                        periodicSpawnTick);
                }
            } catch (Throwable failure) {
                ParallelNaturalSpawner.failed();
                failures.add(failure);
            } finally {
                RegionThreadContext.exit();
            }
        };
    }

    @Unique
    private void optimal$spawnUnowned(List<LevelChunk> unowned,
            NaturalSpawner.SpawnState spawnState, ParallelNaturalSpawner.State parallelState,
            Set<Long> eligibleChunks, boolean periodicSpawnTick, Queue<Throwable> failures) {
        try {
            for (LevelChunk chunk : optimal$spawnChunks(unowned, null, -1, eligibleChunks)) {
                optimal$spawnForChunkParallel(chunk, spawnState, parallelState,
                    periodicSpawnTick);
            }
        } catch (Throwable failure) {
            ParallelNaturalSpawner.failed();
            failures.add(failure);
        }
    }

    @Unique
    private boolean optimal$parallelSpawningSucceeded(Queue<Throwable> failures) {
        if (failures.isEmpty() && RegionTracker.parallelAllowed()
            && ParallelNaturalSpawner.parallelAllowed()) {
            return true;
        }
        Throwable first = failures.peek();
        if (first != null) {
            ParallelNaturalSpawner.degradeToSerial(
                "natural spawning failed on a worker: " + first);
            OPTIMAL$LOGGER.error("optimal: parallel natural spawning failed", first);
        }
        return false;
    }

    @Unique
    private List<LevelChunk> optimal$spawnChunks(List<LevelChunk> source,
                                                  @Nullable Region region, int slice,
                                                  Set<Long> eligibleChunks) {
        List<LevelChunk> chunks = new ArrayList<>(source.size());
        for (LevelChunk chunk : source) {
            ChunkPos pos = chunk.getPos();
            if ((region == null || region.memberInSlice(
                    Long.hashCode(pos.toLong()), slice))
                && eligibleChunks.contains(pos.toLong())
                && this.level.getWorldBorder().isWithinBounds(pos)) {
                chunks.add(chunk);
            }
        }
        Util.shuffle(chunks, this.level.random);
        return chunks;
    }

    @Unique
    private void optimal$spawnForChunkParallel(LevelChunk chunk,
                                                NaturalSpawner.SpawnState spawnState,
                                                ParallelNaturalSpawner.State parallelState,
                                                boolean periodicSpawnTick) {
        ParallelNaturalSpawner.run(parallelState,
            () -> optimal$spawnForChunk(chunk, spawnState, periodicSpawnTick));
    }

    @Unique
    private void optimal$finishChunkFallback(Map<Integer, List<LevelChunk>> regional,
            List<LevelChunk> unowned, LevelRegionIndex index,
            NaturalSpawner.SpawnState spawnState, Set<Long> eligibleChunks,
            long inhabitedDelta, int randomTickSpeed, boolean periodicSpawnTick,
            boolean spawnMobs) {
        for (Region region : index.regionizer().regions()) {
            int slice = region.sliceOn(index.serverTick());
            LevelRegionIndex.RegionRun run = new LevelRegionIndex.RegionRun(region,
                Math.max(1, region.tickDivisor()), slice);
            optimal$tickChunkList(regional.getOrDefault(region.id(), List.of()), run, spawnState,
                eligibleChunks, inhabitedDelta, true, randomTickSpeed, periodicSpawnTick,
                spawnMobs);
            optimal$broadcast(regional.getOrDefault(region.id(), List.of()),
                MainThreadBoundaries.source(index.levelKey(), region.id()));
        }
        optimal$tickChunkList(unowned, null, spawnState, eligibleChunks, inhabitedDelta, true,
            randomTickSpeed, periodicSpawnTick, spawnMobs);
        if (this.level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
            MainThreadBoundaries.measure(MainThreadBoundaries.Boundary.CUSTOM_SPAWNERS,
                MainThreadBoundaries.globalSource(index.levelKey()),
                () -> this.level.tickCustomSpawners(
                    this.spawnEnemies, this.spawnFriendlies));
        }
        optimal$broadcast(unowned, MainThreadBoundaries.source(index.levelKey(), -1));
        if (!RegionTracker.parallelAllowed()) {
            index.drainCapturedScheduledFallback();
        }
        this.optimal$regionalChunkWork = new LevelRegionIndex.RegionalChunkWork(
            run -> { }, () -> { }, region -> { }, () -> { });
    }

    @Unique
    private void optimal$spawnForChunk(LevelChunk chunk, NaturalSpawner.SpawnState spawnState,
                                       boolean periodicSpawnTick) {
        PhaseStats.measure(PhaseStats.Phase.NATURAL_SPAWNING,
            () -> NaturalSpawner.spawnForChunk(this.level, chunk, spawnState,
                this.spawnFriendlies, this.spawnEnemies, periodicSpawnTick));
    }

    @Unique
    private void optimal$getFullChunk(long chunkPos, Consumer<LevelChunk> consumer) {
        ChunkHolder holder = this.chunkMap.getVisibleChunkIfPresent(chunkPos);
        if (holder != null) {
            holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).ifSuccess(consumer);
        }
    }

    @Unique
    @Nullable
    private LevelChunk optimal$getFullChunk(long chunkPos) {
        ChunkHolder holder = this.chunkMap.getVisibleChunkIfPresent(chunkPos);
        if (holder == null) {
            return null;
        }
        return holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK)
            .orElse(null);
    }

    private void optimal$broadcast(List<LevelChunk> chunks, String source) {
        MainThreadBoundaries.measure(
            MainThreadBoundaries.Boundary.CHUNK_PLAYER_BROADCASTS, source, () -> {
                for (LevelChunk chunk : chunks) {
                    ChunkHolder holder = this.chunkMap.getVisibleChunkIfPresent(
                        chunk.getPos().toLong());
                    if (holder != null) {
                        holder.broadcastChanges(chunk);
                    }
                }
            }
        );
    }

    @Inject(
        method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"),
        cancellable = true)
    private void optimal$workerChunkRead(int x, int z, ChunkStatus status, boolean load,
                                         CallbackInfoReturnable<ChunkAccess> cir) {
        if (!RegionWorkers.isWorkerThread()) {
            return;
        }

        cir.setReturnValue(optimal$getChunkForWorker(x, z, status));
    }

    @Override
    @Nullable
    public ChunkAccess optimal$getChunkForWorker(int x, int z, ChunkStatus status) {
        ChunkHolder holder = this.chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
        if (holder != null) {
            ChunkAccess chunk = holder.getChunkIfPresent(status);
            if (chunk != null) {
                return chunk;
            }
        }

        // Not loaded. Falling through would block this worker on the main thread, which is
        // waiting for it.
        if (ParallelNaturalSpawner.active()) {
            ParallelNaturalSpawner.degradeToSerial("a natural-spawn worker needed unloaded "
                + "chunk [" + x + ", " + z + "] at status " + status);
        } else {
            RegionTracker.reportUnavailableChunk(x, z, status);
        }
        return null;
    }
}

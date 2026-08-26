package org.texboobcat.tessellate.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.texboobcat.tessellate.Config;
import org.texboobcat.tessellate.PlatformHooks;
import org.texboobcat.tessellate.api.TessellateApi;
import org.texboobcat.tessellate.region.DeferredMainThreadWork;
import org.texboobcat.tessellate.region.CompatibilityTicks;
import org.texboobcat.tessellate.region.LevelRegionIndex;
import org.texboobcat.tessellate.region.MainThreadBoundaries;
import org.texboobcat.tessellate.region.PhaseStats;
import org.texboobcat.tessellate.region.Region;
import org.texboobcat.tessellate.region.RegionTracker;
import org.texboobcat.tessellate.region.RegionWorkers;
import org.texboobcat.tessellate.region.ThreadLocalRandomSource;
import org.texboobcat.tessellate.region.ThreadLocalBlockRandom;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

// Routes the block-entity phase of the level tick through regions.
//
// The serial fallback redirects only vanilla's iterator. The parallel path snapshots the same
// vanilla lists on the main thread, ticks each region's slice on its owning worker, then compacts
// removed tickers after every claimed region has handed its result back.
//
// The serial redirect remains disjoint from Lithium's shouldTickBlocksAt redirects.
// The parallel path performs the same loaded-chunk check directly and tolerates the null position
// used by Lithium's sleeping ticker delegate.
//
// Client levels are left alone: RegionTracker only holds server levels, so the
// redirect falls through to vanilla there.
@Mixin(Level.class)
public abstract class LevelMixin implements LevelRegionIndex.RegionalLevelAccess,
    ThreadLocalRandomSource.BlockRandomAccess {

    @Shadow
    @org.spongepowered.asm.mixin.Final
    @org.spongepowered.asm.mixin.Mutable
    protected net.minecraft.world.level.redstone.NeighborUpdater neighborUpdater;

    @Shadow
    @Final
    @Mutable
    public RandomSource random;

    @Shadow
    protected int randValue;

    @Unique
    private ThreadLocalBlockRandom tessellate$blockRandom;

    @Shadow
    @Final
    protected List<TickingBlockEntity> blockEntityTickers;

    @Shadow
    @Final
    private List<TickingBlockEntity> pendingBlockEntityTickers;

    @Shadow
    private boolean tickingBlockEntities;

    @Unique
    private int tessellate$regionalBlockEntityPasses;

    @Shadow
    protected abstract void tickBlockEntities();

    // Replaces Level.random with a per-thread generator.
    //
    // Vanilla's is a LegacyRandomSource: a bare mutable seed with no synchronisation.
    // Region workers draw from it constantly, so it has to be split before any of them run
    // concurrently. Done by swapping the field rather than by redirecting call sites, because the
    // field is public and read directly from hundreds of places across vanilla and every mod.
    @Inject(method = "<init>", at = @At("RETURN"))
    private void tessellate$installThreadLocalRandom(CallbackInfo ci) {
        if (Config.threadLocalRandom) {
            this.random = new ThreadLocalRandomSource();
        }
        this.tessellate$blockRandom = new ThreadLocalBlockRandom(this.randValue);
        tessellate$installThreadLocalNeighborUpdater();
    }

    @Override
    public int tessellate$nextBlockRandomBits() {
        return this.tessellate$blockRandom.nextBits();
    }

    @Inject(method = "getBlockRandomPos", at = @At("HEAD"), cancellable = true)
    private void tessellate$threadLocalBlockRandomPos(int x, int y, int z, int yMask,
                                                    CallbackInfoReturnable<BlockPos> cir) {
        int bits = tessellate$nextBlockRandomBits();
        cir.setReturnValue(new BlockPos(x + (bits & 15), y + (bits >> 16 & yMask),
            z + (bits >> 8 & 15)));
    }

    // Vanilla's profiler is stack-based and cannot be shared by region workers.
    @Inject(method = "getProfiler", at = @At("HEAD"), cancellable = true)
    private void tessellate$workerProfiler(CallbackInfoReturnable<ProfilerFiller> cir) {
        if (RegionWorkers.isWorkerThread()) {
            cir.setReturnValue(InactiveProfiler.INSTANCE);
        }
    }

    // Vanilla guards the first comparator-neighbor read but not the block behind a conductor.
    @Redirect(
        method = "updateNeighbourForOutputSignal",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)"
                + "Lnet/minecraft/world/level/block/state/BlockState;",
            ordinal = 1))
    private BlockState tessellate$skipUnloadedOutputSignalChunk(Level level, BlockPos pos) {
        return RegionWorkers.isWorkerThread() && !level.hasChunkAt(pos)
            ? Blocks.VOID_AIR.defaultBlockState()
            : level.getBlockState(pos);
    }

    // Answer before ServerChunkCache so compatibility mods cannot wrap an already-loaded worker
    // read in synchronous-load bookkeeping. The shared lower fast path remains for direct source
    // callers and owns the miss/degrade behavior in both cases.
    @Inject(
        method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"),
        cancellable = true)
    private void tessellate$directWorkerChunkRead(int x, int z, ChunkStatus status, boolean load,
                                                CallbackInfoReturnable<ChunkAccess> cir) {
        if (!Config.directWorkerChunkReads || !RegionWorkers.isWorkerThread()
            || !((Object) this instanceof ServerLevel level)) {
            return;
        }
        ChunkAccess chunk = ((LevelRegionIndex.RegionalChunkAccess) level.getChunkSource())
            .tessellate$getChunkForWorker(x, z, status, load);
        if (chunk == null && load) {
            throw new IllegalStateException("Should always be able to create a chunk!");
        }
        cir.setReturnValue(chunk);
    }

    // Vanilla deliberately returns null off-thread; region workers can read loaded chunks safely.
    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void tessellate$workerBlockEntityRead(BlockPos pos,
                                                CallbackInfoReturnable<BlockEntity> cir) {
        if (!((Object) this instanceof ServerLevel level)) {
            return;
        }
        if (!RegionWorkers.isWorkerThread()) {
            if (Config.asyncRegionLoops) {
                LevelRegionIndex index = RegionTracker.index(level);
                if (index != null) {
                    index.leaseChunk(net.minecraft.world.level.ChunkPos.asLong(
                        pos.getX() >> 4, pos.getZ() >> 4));
                }
            }
            return;
        }
        if (level.isOutsideBuildHeight(pos)) {
            cir.setReturnValue(null);
            return;
        }
        ChunkAccess chunk = level.getChunkSource().getChunk(
            pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        cir.setReturnValue(chunk instanceof LevelChunk levelChunk
            ? levelChunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE)
            : null);
    }

    // Main-thread spatial writes wait only for the region containing the target block.
    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"))
    private void tessellate$leaseMainThreadBlockWrite(BlockPos pos, BlockState state, int flags,
                                                    int recursionLeft,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (!Config.asyncRegionLoops || RegionWorkers.isWorkerThread()
            || !((Object) this instanceof ServerLevel level)) {
            return;
        }
        LevelRegionIndex index = RegionTracker.index(level);
        if (index != null) {
            index.leaseChunk(net.minecraft.world.level.ChunkPos.asLong(
                pos.getX() >> 4, pos.getZ() >> 4));
        }
    }

    @Inject(method = "addBlockEntityTicker", at = @At("HEAD"), cancellable = true)
    private void tessellate$deferNewTicker(TickingBlockEntity ticker, CallbackInfo ci) {
        if (!RegionWorkers.isWorkerThread()) {
            tessellate$leaseTicker(ticker);
            return;
        }
        Level self = (Level) (Object) this;
        DeferredMainThreadWork.defer(
            MainThreadBoundaries.Boundary.BLOCK_ENTITY_REGISTRATION,
            () -> self.addBlockEntityTicker(ticker));
        ci.cancel();
    }

    @Unique
    private void tessellate$leaseTicker(TickingBlockEntity ticker) {
        if (Config.asyncRegionLoops && ticker.getPos() != null) {
            tessellate$leasePos(ticker.getPos());
        }
    }

    @Override
    public void tessellate$leasePos(BlockPos pos) {
        if ((Object) this instanceof ServerLevel level) {
            LevelRegionIndex index = RegionTracker.index(level);
            if (index != null) {
                index.leaseChunk(net.minecraft.world.level.ChunkPos.asLong(
                    pos.getX() >> 4, pos.getZ() >> 4));
            }
        }
    }

    @Override
    public void tessellate$runVanillaBlockEntityPass() {
        PhaseStats.measure(PhaseStats.Phase.BLOCK_ENTITIES, this::tickBlockEntities);
    }

    @Override
    public Map<Integer, List<TickingBlockEntity>> tessellate$prepareRegionalBlockEntities(
        LevelRegionIndex index) {
        this.tessellate$regionalBlockEntityPasses++;
        this.tickingBlockEntities = true;
        PlatformHooks.drainFreshBlockEntities((Level) (Object) this, blockEntity -> {
            if (!blockEntity.isRemoved() && blockEntity.hasLevel()) {
                if (Config.asyncRegionLoops) {
                    tessellate$leasePos(blockEntity.getBlockPos());
                }
                PlatformHooks.onBlockEntityLoad(blockEntity);
            }
        });
        if (!this.pendingBlockEntityTickers.isEmpty()) {
            this.blockEntityTickers.addAll(this.pendingBlockEntityTickers);
            this.pendingBlockEntityTickers.clear();
        }
        return index.snapshotBlockEntityTickers(this.blockEntityTickers);
    }

    @Override
    public void tessellate$tickRegionalBlockEntities(@Nullable Region region,
                                                   List<TickingBlockEntity> tickers) {
        Level self = (Level) (Object) this;
        long start = System.nanoTime();
        long phaseStart = PhaseStats.begin(PhaseStats.Phase.BLOCK_ENTITIES);
        try {
            boolean runsNormally = self.tickRateManager().runsNormally();
            for (TickingBlockEntity ticker : tickers) {
                BlockPos pos = ticker.getPos();
                boolean eligible = !ticker.isRemoved() && runsNormally && pos != null
                    && self.shouldTickBlocksAt(pos);
                if (eligible) {
                    tessellate$tickBlockEntity(self, ticker, pos);
                }
            }
        } catch (Throwable failure) {
            PhaseStats.failed(PhaseStats.Phase.BLOCK_ENTITIES);
            throw failure;
        } finally {
            PhaseStats.end(PhaseStats.Phase.BLOCK_ENTITIES, phaseStart);
            if (region != null) {
                region.recordBlockEntityTick(System.nanoTime() - start);
            }
        }
    }

    @Unique
    private static void tessellate$tickBlockEntity(Level level, TickingBlockEntity ticker,
                                                   BlockPos pos) {
        BlockEntity blockEntity = RegionWorkers.isWorkerThread()
            || TessellateApi.hasMainThreadBlockEntityTypes() ? level.getBlockEntity(pos) : null;
        if (blockEntity != null && CompatibilityTicks.deferBlockEntity(
                blockEntity.getType(), () -> tessellate$tickBlockEntity(level, ticker, pos))) {
            return;
        }
        if (!ticker.isRemoved() && level.tickRateManager().runsNormally()
            && level.shouldTickBlocksAt(pos)) {
            if (blockEntity == null) {
                ticker.tick();
            } else {
                CompatibilityTicks.tickBlockEntity(blockEntity.getType(), ticker::tick);
            }
        }
    }

    @Override
    public void tessellate$finishRegionalBlockEntities() {
        if (--this.tessellate$regionalBlockEntityPasses > 0) {
            return;
        }
        this.tessellate$regionalBlockEntityPasses = 0;
        this.blockEntityTickers.removeIf(TickingBlockEntity::isRemoved);
        this.tickingBlockEntities = false;
    }

    // Gives each thread its own neighbor-update walker.
    //
    // Vanilla's is one per level and holds a pending-update stack, which an entity tick reaches
    // whenever a mob steps on a pressure plate. Two workers walking the same stack dereference an
    // entry the other already popped.
    //
    // Left alone if another mod has replaced the updater with something that is not vanilla's,
    // since guessing at a third party's threading model is worse than not touching it.
    private void tessellate$installThreadLocalNeighborUpdater() {
        if (!Config.threadLocalRandom) {
            return;
        }
        if (!(this.neighborUpdater instanceof net.minecraft.world.level.redstone.CollectingNeighborUpdater existing)) {
            return;
        }
        int maxChained =
            ((CollectingNeighborUpdaterAccessor) existing).tessellate$getMaxChainedNeighborUpdates();
        this.neighborUpdater = new org.texboobcat.tessellate.region.ThreadLocalNeighborUpdater(
            (net.minecraft.world.level.Level) (Object) this, maxChained);
    }

    @Redirect(
        method = "tickBlockEntities",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
    private Iterator<TickingBlockEntity> tessellate$orderTickersByRegion(List<TickingBlockEntity> tickers) {
        if (Config.regionsEnabled && Config.scopedBlockEntityTicking
            && (Object) this instanceof ServerLevel serverLevel) {
            LevelRegionIndex index = RegionTracker.index(serverLevel);
            if (index != null) {
                return index.orderBlockEntityTickers(tickers);
            }
        }
        return tickers.iterator();
    }
}

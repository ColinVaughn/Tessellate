package org.texboobcat.optimal.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import org.objectweb.asm.Opcodes;
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
import org.texboobcat.optimal.Config;
import org.texboobcat.optimal.region.LevelRegionIndex;
import org.texboobcat.optimal.region.ConcurrentPathTypeCache;
import org.texboobcat.optimal.region.PhaseStats;
import org.texboobcat.optimal.region.RegionTracker;
import org.texboobcat.optimal.region.RegionWorkers;
import org.texboobcat.optimal.region.RegionalLevelTicks;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Routes the entity phase of the level tick through regions.
//
// Vanilla's ServerLevel.tick iterates one flat entityTickList. This redirects
// that single call so the same per-entity lambda is applied region by region instead. The work
// done per entity is byte-for-byte vanilla; only the grouping changes, and each region gets timed
// separately so /optimal regions can attribute cost.
//
// With all parallel prerequisites enabled this call dispatches one complete region envelope.
// Independent mode lets that envelope survive the server tick; staged and sequential grouping
// remain fallback paths.
//
// Behavior note. Entity order within a region is unchanged, but entities are
// now grouped by region rather than interleaved in level-wide insertion order. Regions are more
// than mergeRadius sections apart and cannot interact, so no entity observes another's
// reordering. The visible effect is the order in which entities draw from the shared
// level.random, so random outcomes differ from vanilla even though the distribution does
// not. Bit-identical replay is therefore not a goal; level.random becoming per-region is
// on the list of state still to split.
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Shadow
    @Final
    @Mutable
    private LevelTicks<Block> blockTicks;

    @Shadow
    @Final
    @Mutable
    private LevelTicks<Fluid> fluidTicks;

    @Shadow
    private boolean isPositionTickingWithEntitiesLoaded(long chunkPos) {
        throw new AssertionError();
    }

    @Shadow
    @Final
    @Mutable
    private PathTypeCache pathTypesByPosCache;

    @Shadow
    private volatile boolean isUpdatingNavigations;

    @Shadow
    @Final
    @Mutable
    private Set<Mob> navigatingMobs;

    @Unique
    private final ThreadLocal<Boolean> optimal$updatingNavigations =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void optimal$installConcurrentNavigationState(CallbackInfo ci) {
        this.pathTypesByPosCache = new ConcurrentPathTypeCache();
        // A callback from one idle region can run on the main thread while another region
        // scans this level-wide set after a block update.
        this.navigatingMobs = ConcurrentHashMap.newKeySet();
        if (Config.regionsEnabled) {
            ServerLevel self = (ServerLevel) (Object) this;
            this.blockTicks = new RegionalLevelTicks<>(
                this::isPositionTickingWithEntitiesLoaded,
                self::getProfiler);
            this.fluidTicks = new RegionalLevelTicks<>(
                this::isPositionTickingWithEntitiesLoaded,
                self::getProfiler);
        }
    }

    // Keep vanilla's recursion flag per worker. The main thread retains the original field so
    // entity lifecycle callbacks preserve their vanilla diagnostics.
    @Redirect(
        method = "sendBlockUpdated",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/server/level/ServerLevel;isUpdatingNavigations:Z",
            opcode = Opcodes.GETFIELD))
    private boolean optimal$getNavigationUpdateFlag(ServerLevel level) {
        return RegionWorkers.isWorkerThread()
            ? this.optimal$updatingNavigations.get()
            : this.isUpdatingNavigations;
    }

    @Redirect(
        method = "sendBlockUpdated",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/server/level/ServerLevel;isUpdatingNavigations:Z",
            opcode = Opcodes.PUTFIELD))
    private void optimal$setNavigationUpdateFlag(ServerLevel level, boolean updating) {
        if (!RegionWorkers.isWorkerThread()) {
            this.isUpdatingNavigations = updating;
        } else if (updating) {
            this.optimal$updatingNavigations.set(Boolean.TRUE);
        } else {
            this.optimal$updatingNavigations.remove();
        }
    }

    // Replays entity lifecycle callbacks a worker deferred, before anything reads what they update.
    //
    // Placed at the head of the level tick because the first reader is chunkSource.tick,
    // which walks ChunkMap.entityMap. Concurrent
    // tracking updates surfaced here as an unexplained NullPointerException.
    @Inject(method = "tick", at = @At("HEAD"))
    private void optimal$replayDeferredCallbacks(java.util.function.BooleanSupplier hasTimeLeft,
                                                 CallbackInfo ci) {
        LevelRegionIndex index = RegionTracker.index((ServerLevel) (Object) this);
        if (index != null) {
            index.drainDeferredCallbacks();
        }
        org.texboobcat.optimal.region.DeferredMainThreadWork.drain();

        // The throttle steers toward a target tick time, so it needs to know what the tick
        // actually costs. The server already smooths this over the last hundred ticks.
        ServerLevel self = (ServerLevel) (Object) this;
        if (self.getServer() != null) {
            RegionTracker.recordServerTickNanos(self.getServer().getAverageTickTimeNanos());
        }
    }

    // Routes block events into the queue owned by their current region. The router retains one
    // ordered fallback queue when the phase switch is off.
    @Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
    private void optimal$routeBlockEvent(net.minecraft.core.BlockPos pos,
                                         net.minecraft.world.level.block.Block block,
                                         int eventId, int eventParam, CallbackInfo ci) {
        LevelRegionIndex index = RegionTracker.index((ServerLevel) (Object) this);
        if (index == null) {
            return;
        }
        index.blockEvents().add(pos, block, eventId, eventParam);
        ci.cancel();
    }

    // Piston and note-block callbacks call this positional overload. It performs NeoForge hooks
    // and walks the global player list, so keep that network side effect on main.
    @Inject(
        method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
        at = @At("HEAD"),
        cancellable = true)
    private void optimal$deferPositionalSound(Player except, double x, double y, double z,
                                               Holder<SoundEvent> sound, SoundSource source,
                                               float volume, float pitch, long seed,
                                               CallbackInfo ci) {
        if (!RegionWorkers.isWorkerThread()) {
            return;
        }
        ServerLevel self = (ServerLevel) (Object) this;
        org.texboobcat.optimal.region.DeferredMainThreadWork.defer(
            org.texboobcat.optimal.region.MainThreadBoundaries.Boundary
                .CHUNK_PLAYER_BROADCASTS,
            () -> self.playSeededSound(except, x, y, z, sound, source, volume, pitch, seed));
        ci.cancel();
    }

    @Inject(method = "unload", at = @At("HEAD"))
    private void optimal$leaseChunkUnload(LevelChunk chunk, CallbackInfo ci) {
        if (!Config.asyncRegionLoops || RegionWorkers.isWorkerThread()) {
            return;
        }
        LevelRegionIndex index = RegionTracker.index((ServerLevel) (Object) this);
        if (index != null) {
            index.leaseChunk(chunk.getPos().toLong());
        }
    }

    // Keeps NeoForge's entity-join event and level-global entity indexes on the main thread.
    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void optimal$deferEntityAddOffThread(Entity entity,
                                                  CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (org.texboobcat.optimal.region.ParallelNaturalSpawner.captureEntityAdd(
                () -> self.addFreshEntity(entity))) {
            cir.setReturnValue(true);
            return;
        }
        if (!Config.shardEntityStorage || !RegionWorkers.isWorkerThread()) {
            return;
        }
        org.texboobcat.optimal.region.DeferredMainThreadWork.defer(
            org.texboobcat.optimal.region.MainThreadBoundaries.Boundary.ENTITY_LIFECYCLE,
            () -> self.addFreshEntity(entity));
        cir.setReturnValue(true);
    }

    // Rejects stale or unowned containers defensively. The regional router normally ensures a
    // child contains only containers owned by the bound region.
    @Inject(method = "isPositionTickingWithEntitiesLoaded", at = @At("RETURN"), cancellable = true)
    private void optimal$scopeScheduledTicksToRegion(long chunkPos, CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue()) || !Config.scopedScheduledTicks) {
            return;
        }
        LevelRegionIndex index = RegionTracker.index((ServerLevel) (Object) this);
        if ((LevelRegionIndex.hasBoundRegion() && !LevelRegionIndex.boundRegionOwns(chunkPos))
            || (LevelRegionIndex.isUnownedTickPass() && index != null && index.ownsChunk(chunkPos))) {
            cir.setReturnValue(false);
        }
    }

    // Drains scheduled block and fluid ticks region by region instead of in one level-wide pass.
    //
    // One redirect covers both call sites, since the handler receives the LevelTicks
    // instance. Each region gets its own maxAllowedTicks budget rather than sharing one
    // level-wide budget, so a region saturating the cap can no longer starve the others.
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V"))
    private void optimal$tickScheduledByRegion(LevelTicks ticks, long gameTime, int maxAllowedTicks,
                                               BiConsumer ticker) {
        LevelRegionIndex index = Config.scopedScheduledTicks
            ? RegionTracker.index((ServerLevel) (Object) this)
            : null;
        if (index == null) {
            optimal$measureScheduled(ticks, gameTime, maxAllowedTicks, ticker);
            return;
        }
        if (index.usesFullRegionTick()) {
            index.captureScheduledDrain(
                budget -> optimal$measureScheduled(ticks, gameTime, budget, ticker),
                maxAllowedTicks);
            return;
        }
        index.runPerRegionThenUnbound(
            budget -> optimal$measureScheduled(ticks, gameTime, budget, ticker),
            maxAllowedTicks);
    }

    @Unique
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void optimal$measureScheduled(LevelTicks ticks, long gameTime, int budget,
                                          BiConsumer ticker) {
        PhaseStats.Phase domain = ticks == this.blockTicks
            ? PhaseStats.Phase.SCHEDULED_BLOCK_TICKS
            : PhaseStats.Phase.SCHEDULED_FLUID_TICKS;
        PhaseStats.measure(PhaseStats.Phase.SCHEDULED_TICKS,
            () -> PhaseStats.measure(domain, () -> ticks.tick(gameTime, budget, ticker)));
    }

    @WrapMethod(method = "runBlockEvents")
    private void optimal$runBlockEventsByRegion(Operation<Void> original) {
        LevelRegionIndex index = RegionTracker.index((ServerLevel) (Object) this);
        if (index == null) {
            PhaseStats.measure(PhaseStats.Phase.BLOCK_EVENTS, original::call);
            return;
        }

        // Drain anything queued before RegionTracker saw the level. Normal runtime events never
        // enter vanilla's private set once the index exists.
        original.call();
        Runnable drain = index.blockEvents()::drainCurrent;
        if (!index.blockEvents().regionalRouting()) {
            drain.run();
        } else if (index.usesFullRegionTick()) {
            index.captureBlockEventDrain(drain);
        } else {
            index.runPerRegionThenUnbound(drain);
        }
    }

    @WrapMethod(method = "clearBlockEvents")
    private void optimal$clearRegionalBlockEvents(BoundingBox area, Operation<Void> original) {
        LevelRegionIndex index = RegionTracker.index((ServerLevel) (Object) this);
        if (index == null) {
            original.call(area);
            return;
        }
        if (RegionWorkers.isWorkerThread()) {
            ServerLevel self = (ServerLevel) (Object) this;
            org.texboobcat.optimal.region.DeferredMainThreadWork.defer(
                org.texboobcat.optimal.region.MainThreadBoundaries.Boundary
                    .CROSS_REGION_WRITES,
                () -> self.clearBlockEvents(area));
            return;
        }
        original.call(area);
        index.blockEvents().clearArea(area);
    }

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tickBlockEntities()V"))
    private void optimal$skipVanillaBlockEntityPass(ServerLevel level) {
        LevelRegionIndex index = RegionTracker.index(level);
        // A worker can degrade the *next* tick to serial. The envelope already ran this tick,
        // so key this decision to execution, not the now-disabled parallel gate.
        if (index == null || !index.fullRegionTickExecuted()) {
            ((LevelRegionIndex.RegionalLevelAccess) level).optimal$runVanillaBlockEntityPass();
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void optimal$finishIdleFullRegionTick(java.util.function.BooleanSupplier hasTimeLeft,
                                                  CallbackInfo ci) {
        LevelRegionIndex index = RegionTracker.index((ServerLevel) (Object) this);
        if (index != null) {
            index.finishFullRegionTickIfNeeded();
        }
    }

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void optimal$tickEntitiesByRegion(EntityTickList tickList, Consumer<Entity> consumer) {
        if (Config.regionsEnabled && Config.scopedEntityTicking) {
            LevelRegionIndex index = RegionTracker.index((ServerLevel) (Object) this);
            if (index != null) {
                index.tickEntitiesByRegion(consumer);
                return;
            }
        }
        PhaseStats.measure(PhaseStats.Phase.ENTITIES, () -> tickList.forEach(consumer));
    }
}

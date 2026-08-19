package org.texboobcat.optimal.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.texboobcat.optimal.Config;
import org.texboobcat.optimal.region.LevelRegionIndex;
import org.texboobcat.optimal.region.RegionTracker;
import org.texboobcat.optimal.region.RegionWorkers;

// Feeds entity-ticking status changes to the regionizer as they happen.
//
// Replaces the full rescan, which was O(loaded chunks) every tick. That measured 0.22 ms at
// 137 ticking chunks and 0.55 ms at 361, about 1.5 us per loaded chunk. A real server with
// 5,000 to 10,000 loaded chunks would have paid 7 to 15 ms per tick just to notice that nothing
// had changed.
//
// onFullChunkStatusChange is called for both promotion and demotion, in each case with
// the status the chunk is moving to, and both paths run on the main thread
// (ChunkHolder.updateFutures is driven by ChunkMap.mainThreadExecutor). The
// regionizer only queues here; the queue is applied in LevelRegionIndex.tick.
//
// A periodic full rescan still runs as a self-heal and cross-checks this path, so a missed
// transition surfaces as a logged discrepancy rather than as a silently wrong region map.
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Shadow
    @Final
    ServerLevel level;

    @Inject(method = "onFullChunkStatusChange", at = @At("HEAD"))
    private void optimal$trackEntityTicking(ChunkPos chunkPos, FullChunkStatus status, CallbackInfo ci) {
        RegionTracker.onChunkStatusChange(
            this.level, chunkPos.toLong(), status.isOrAfter(FullChunkStatus.ENTITY_TICKING));
    }

    // Autosave is opportunistic. If the candidate's region is still finishing an asynchronous
    // tick, retry it later instead of turning autosave into a main-thread wait.
    @Inject(method = "saveChunkIfNeeded", at = @At("HEAD"), cancellable = true)
    private void optimal$deferBusyAutosave(ChunkHolder holder,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (!Config.asyncRegionLoops || RegionWorkers.isWorkerThread()) {
            return;
        }
        LevelRegionIndex index = RegionTracker.index(this.level);
        if (index != null && !index.chunkIdle(holder.getPos().toLong())) {
            RegionTracker.recordDeferredAutosave();
            cir.setReturnValue(false);
        }
    }

    // Direct saves include vanilla unload and compatibility fallbacks. Lease only the owning
    // region; ordinary autosave reaches here only after the non-blocking idle check above.
    @Inject(method = "save", at = @At("HEAD"))
    private void optimal$leaseChunkSave(ChunkAccess chunk,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (!Config.asyncRegionLoops || RegionWorkers.isWorkerThread()) {
            return;
        }
        LevelRegionIndex index = RegionTracker.index(this.level);
        if (index != null) {
            index.leaseChunk(chunk.getPos().toLong());
        }
    }
}

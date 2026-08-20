package org.texboobcat.tessellate.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.texboobcat.tessellate.region.DeferredEntityCallbacks;
import org.texboobcat.tessellate.region.LevelRegionIndex;
import org.texboobcat.tessellate.region.RegionTracker;
import org.texboobcat.tessellate.region.RegionWorkers;

// Keeps per-region entity lists in step with the level's own tick list, and keeps every entity
// lifecycle callback on the main thread.
//
// ServerLevel$EntityCallbacks is where vanilla adds to and removes from
// entityTickList, and where PersistentEntitySectionManager reports that an entity
// has moved to a different chunk section. Those transitions are exactly what a per-region list
// needs. They are also where a single entity crossing a section writes half a dozen
// level-global containers that are not thread-safe.
//
// So a worker does not run them at all: it queues them and the main thread replays them. See
// DeferredEntityCallbacks for why that is one invariant rather than six concurrent
// containers.
//
// The level is resolved through entity.level() rather than by reaching for the
// enclosing ServerLevel instance, which an inner-class mixin cannot access cleanly.
@Mixin(targets = "net.minecraft.server.level.ServerLevel$EntityCallbacks")
public abstract class EntityCallbacksMixin {

    // ---- deferral ----------------------------------------------------------------------------

    @Inject(method = "onCreated", at = @At("HEAD"), cancellable = true)
    private void tessellate$deferCreated(Entity entity, CallbackInfo ci) {
        tessellate$deferIfOffThread(entity, DeferredEntityCallbacks.Kind.CREATED, ci);
    }

    @Inject(method = "onDestroyed", at = @At("HEAD"), cancellable = true)
    private void tessellate$deferDestroyed(Entity entity, CallbackInfo ci) {
        tessellate$deferIfOffThread(entity, DeferredEntityCallbacks.Kind.DESTROYED, ci);
    }

    @Inject(method = "onTickingStart", at = @At("HEAD"), cancellable = true)
    private void tessellate$deferTickingStart(Entity entity, CallbackInfo ci) {
        tessellate$deferIfOffThread(entity, DeferredEntityCallbacks.Kind.TICKING_START, ci);
    }

    @Inject(method = "onTickingEnd", at = @At("HEAD"), cancellable = true)
    private void tessellate$deferTickingEnd(Entity entity, CallbackInfo ci) {
        tessellate$deferIfOffThread(entity, DeferredEntityCallbacks.Kind.TICKING_END, ci);
    }

    @Inject(method = "onTrackingStart", at = @At("HEAD"), cancellable = true)
    private void tessellate$deferTrackingStart(Entity entity, CallbackInfo ci) {
        tessellate$deferIfOffThread(entity, DeferredEntityCallbacks.Kind.TRACKING_START, ci);
    }

    @Inject(method = "onTrackingEnd", at = @At("HEAD"), cancellable = true)
    private void tessellate$deferTrackingEnd(Entity entity, CallbackInfo ci) {
        tessellate$deferIfOffThread(entity, DeferredEntityCallbacks.Kind.TRACKING_END, ci);
    }

    @Inject(method = "onSectionChange", at = @At("HEAD"), cancellable = true)
    private void tessellate$deferSectionChange(Entity entity, CallbackInfo ci) {
        tessellate$deferIfOffThread(entity, DeferredEntityCallbacks.Kind.SECTION_CHANGE, ci);
    }

    // Queues the callback and cancels it when a region worker raised it.
    //
    // Detection is on the worker thread type, not on the region binding: serial region ticking
    // enters that binding on the main thread too, and testing the binding would defer everything
    // even with parallel ticking off.
    private static void tessellate$deferIfOffThread(Entity entity, DeferredEntityCallbacks.Kind kind,
                                                 CallbackInfo ci) {
        if (!RegionWorkers.isWorkerThread()) {
            return;
        }
        LevelRegionIndex index = tessellate$index(entity);
        if (index == null) {
            return;
        }
        index.deferredCallbacks().defer(kind, entity);
        ci.cancel();
    }

    // ---- per-region membership ---------------------------------------------------------------
    //
    // TAIL rather than HEAD, so a deferred callback updates region membership when it is replayed
    // rather than when it was raised. Membership and the level's own lists therefore never
    // disagree about which tick a transition happened on.

    @Inject(method = "onTickingStart", at = @At("TAIL"))
    private void tessellate$onTickingStart(Entity entity, CallbackInfo ci) {
        LevelRegionIndex index = tessellate$index(entity);
        if (index != null) {
            index.onEntityStartTicking(entity);
        }
    }

    @Inject(method = "onTickingEnd", at = @At("TAIL"))
    private void tessellate$onTickingEnd(Entity entity, CallbackInfo ci) {
        LevelRegionIndex index = tessellate$index(entity);
        if (index != null) {
            index.onEntityStopTicking(entity);
        }
    }

    @Inject(method = "onSectionChange", at = @At("TAIL"))
    private void tessellate$onSectionChange(Entity entity, CallbackInfo ci) {
        LevelRegionIndex index = tessellate$index(entity);
        if (index != null) {
            index.onEntitySectionChange(entity);
        }
    }

    private static LevelRegionIndex tessellate$index(Entity entity) {
        return entity.level() instanceof ServerLevel serverLevel
            ? RegionTracker.index(serverLevel)
            : null;
    }
}

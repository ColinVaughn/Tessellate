package org.texboobcat.optimal.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.texboobcat.optimal.Config;
import org.texboobcat.optimal.region.DeferredMainThreadWork;
import org.texboobcat.optimal.region.MainThreadBoundaries;
import org.texboobcat.optimal.region.RegionWorkers;

import java.util.Set;

// Long-distance and cross-dimension teleports are handed back to the main thread.
@Mixin(Entity.class)
public abstract class EntityMixin {

    @Inject(method = "changeDimension", at = @At("HEAD"), cancellable = true)
    private void optimal$deferDimensionChange(DimensionTransition transition,
                                               CallbackInfoReturnable<Entity> cir) {
        if (!Config.asyncRegionLoops || !RegionWorkers.isWorkerThread()) {
            return;
        }
        Entity self = (Entity) (Object) this;
        DeferredMainThreadWork.defer(MainThreadBoundaries.Boundary.TELEPORT_DIMENSION,
            () -> self.changeDimension(transition));
        cir.setReturnValue(null);
    }

    @Inject(
        method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDD"
            + "Ljava/util/Set;FF)Z",
        at = @At("HEAD"), cancellable = true)
    private void optimal$deferLevelTeleport(ServerLevel level, double x, double y, double z,
                                             Set<RelativeMovement> movement, float yaw, float pitch,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (!Config.asyncRegionLoops || !RegionWorkers.isWorkerThread()) {
            return;
        }
        Entity self = (Entity) (Object) this;
        DeferredMainThreadWork.defer(MainThreadBoundaries.Boundary.TELEPORT_DIMENSION,
            () -> self.teleportTo(level, x, y, z, movement, yaw, pitch));
        cir.setReturnValue(true);
    }

    @Inject(method = "teleportTo(DDD)V", at = @At("HEAD"), cancellable = true)
    private void optimal$deferLocalTeleport(double x, double y, double z, CallbackInfo ci) {
        if (!Config.asyncRegionLoops || !RegionWorkers.isWorkerThread()) {
            return;
        }
        Entity self = (Entity) (Object) this;
        DeferredMainThreadWork.defer(MainThreadBoundaries.Boundary.TELEPORT_DIMENSION,
            () -> self.teleportTo(x, y, z));
        ci.cancel();
    }
}

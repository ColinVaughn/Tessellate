package org.texboobcat.optimal.mixin;

import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.texboobcat.optimal.Config;
import org.texboobcat.optimal.region.DeferredMainThreadWork;
import org.texboobcat.optimal.region.MainThreadBoundaries;
import org.texboobcat.optimal.region.RegionWorkers;
import org.texboobcat.optimal.region.RegionalLevelTicks;

// Defers writes into ordinary, non-owned LevelTicks instances. Regional children accept local
// worker writes directly; their router hands cross-owner writes to the destination region.
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin<T> {

    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void optimal$deferScheduleOffThread(ScheduledTick<T> tick, CallbackInfo ci) {
        if (!Config.shardEntityStorage || !RegionWorkers.isWorkerThread()) {
            return;
        }
        @SuppressWarnings("unchecked")
        LevelTicks<T> self = (LevelTicks<T>) (Object) this;
        if (self instanceof RegionalLevelTicks.WorkerOwned) {
            return;
        }
        DeferredMainThreadWork.defer(MainThreadBoundaries.Boundary.CROSS_REGION_WRITES,
            () -> self.schedule(tick));
        ci.cancel();
    }
}

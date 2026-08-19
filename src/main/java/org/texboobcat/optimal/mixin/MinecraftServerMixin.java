package org.texboobcat.optimal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.slf4j.Logger;
import org.texboobcat.optimal.Config;
import org.texboobcat.optimal.region.MainThreadBoundaries;
import org.texboobcat.optimal.region.RegionTracker;
import org.texboobcat.optimal.region.RegionWorkers;

// Save snapshots are rare global boundaries and must not observe a half-ticked region.
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Unique
    private static final Logger OPTIMAL$LOGGER = LogUtils.getLogger();

    @WrapMethod(method = "saveAllChunks")
    private boolean optimal$runSaveBoundary(boolean suppressLogs, boolean flush, boolean force,
                                            Operation<Boolean> original) {
        if (Config.regionsEnabled && !RegionWorkers.isWorkerThread()) {
            return MainThreadBoundaries.call(MainThreadBoundaries.Boundary.SAVE_BARRIER,
                "global/main", () -> {
                    RegionTracker.quiesceAndDrain();
                    return original.call(suppressLogs, flush, force);
                });
        }
        return original.call(suppressLogs, flush, force);
    }

    @WrapMethod(method = "stopServer")
    private void optimal$runShutdownBoundary(Operation<Void> original) {
        if (Config.regionsEnabled && !RegionWorkers.isWorkerThread()) {
            MainThreadBoundaries.measure(MainThreadBoundaries.Boundary.SHUTDOWN_BARRIER,
                "global/main", () -> {
                    RegionTracker.quiesceAndDrain();
                    original.call();
                });
            MainThreadBoundaries.Snapshot shutdown = MainThreadBoundaries.snapshot(
                MainThreadBoundaries.Boundary.SHUTDOWN_BARRIER);
            OPTIMAL$LOGGER.info("optimal: shutdown boundary complete; calls={}, main={} ms, "
                    + "deferred pending={}", shutdown.directCalls(),
                shutdown.mainNanos() / 1_000_000.0,
                org.texboobcat.optimal.region.DeferredMainThreadWork.pendingCount());
            return;
        }
        original.call();
    }
}

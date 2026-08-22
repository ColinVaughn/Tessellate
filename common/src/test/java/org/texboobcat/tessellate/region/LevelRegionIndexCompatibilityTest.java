package org.texboobcat.tessellate.region;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.texboobcat.tessellate.Config;
import org.texboobcat.tessellate.api.TessellateApi;
import org.texboobcat.tessellate.api.TessellateApiInternal;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LevelRegionIndexCompatibilityTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void configuredEntityTickDefersFromWorker() {
        AtomicBoolean deferred = new AtomicBoolean();
        AtomicBoolean ran = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Config.reset();
        TessellateApiInternal.configureMainThreadEntities(List.of("minecraft:armadillo"));
        RegionWorkers.start();
        try {
            RegionWorkers.submit(() -> deferred.set(CompatibilityTicks.deferEntity(
                EntityType.ARMADILLO, () -> ran.set(true))), failure::set);
            RegionWorkers.awaitIdle();

            assertNull(failure.get());
            assertTrue(deferred.get());
            assertFalse(ran.get());
            assertEquals(1, DeferredMainThreadWork.pendingCount());
            assertEquals(1, MainThreadBoundaries.snapshot(
                MainThreadBoundaries.Boundary.MOD_COMPATIBILITY).pending());

            DeferredMainThreadWork.drain();
            assertTrue(ran.get());
        } finally {
            RegionWorkers.stop();
            DeferredMainThreadWork.reset();
            MainThreadBoundaries.reset();
            TessellateApiInternal.configureMainThreadEntities(List.of());
        }
    }

    @Test
    void wholeTickFallbackWaitsForGlobalWorkerQuiescence() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        CountDownLatch queued = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Config.reset();
        RegionWorkers.start();
        try {
            RegionWorkers.submit(() -> {
                DeferredMainThreadWork.deferGlobal(
                    MainThreadBoundaries.Boundary.MOD_COMPATIBILITY,
                    () -> ran.set(true));
                queued.countDown();
                try {
                    release.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }, failure -> { });
            assertTrue(queued.await(1, TimeUnit.SECONDS));

            CompletableFuture<Void> drain =
                CompletableFuture.runAsync(DeferredMainThreadWork::drain);
            assertThrows(TimeoutException.class, () -> drain.get(50, TimeUnit.MILLISECONDS));
            assertFalse(ran.get());

            release.countDown();
            drain.get(1, TimeUnit.SECONDS);
            assertTrue(ran.get());
        } finally {
            release.countDown();
            RegionWorkers.stop();
            DeferredMainThreadWork.reset();
            MainThreadBoundaries.reset();
        }
    }
}

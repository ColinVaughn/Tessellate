package org.texboobcat.tessellate.region;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.texboobcat.tessellate.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityTicksTest {

    @AfterEach
    void restoreDefaults() {
        Config.reset();
    }

    @Test
    void entityTicksRemainParallelByDefault() throws Exception {
        Config.reset();
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> first = workers.submit(() -> runBlockingTick(bothEntered, release));
            Future<?> second = workers.submit(() -> runBlockingTick(bothEntered, release));

            assertTrue(bothEntered.await(1, TimeUnit.SECONDS));
            release.countDown();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void compatibilityModeSerializesOnlyEntityTicks() throws Exception {
        Config.reset();
        Config.serializeEntityTicks = true;
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch unrelatedWorkRan = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> first = workers.submit(() -> runBlockingTick(firstEntered, releaseFirst));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

            workers.submit(unrelatedWorkRan::countDown);
            assertTrue(unrelatedWorkRan.await(1, TimeUnit.SECONDS));

            Future<?> second = workers.submit(() -> {
                secondStarted.countDown();
                CompatibilityTicks.runEntityTick(secondEntered::countDown);
            });
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
            assertFalse(secondEntered.await(50, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
    }

    private static void runBlockingTick(CountDownLatch entered, CountDownLatch release) {
        CompatibilityTicks.runEntityTick(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        });
    }
}

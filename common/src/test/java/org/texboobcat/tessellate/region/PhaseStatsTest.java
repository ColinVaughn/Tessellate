package org.texboobcat.tessellate.region;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhaseStatsTest {

    @AfterEach
    void reset() {
        PhaseStats.reset();
    }

    @Test
    void recordsActualOverlapAndFailures() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable measured = () -> PhaseStats.measure(PhaseStats.Phase.ENTITIES, () -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
        Thread first = new Thread(measured);
        Thread second = new Thread(measured);
        first.start();
        second.start();
        boolean overlapped = entered.await(1, TimeUnit.SECONDS);
        release.countDown();
        first.join();
        second.join();
        assertEquals(true, overlapped);

        assertThrows(IllegalStateException.class, () -> PhaseStats.measure(
            PhaseStats.Phase.ENTITIES, () -> {
            throw new IllegalStateException("expected");
        }));

        PhaseStats.Snapshot snapshot = PhaseStats.snapshot(PhaseStats.Phase.ENTITIES);
        assertEquals(3L, snapshot.mainCalls());
        assertEquals(2, snapshot.maxConcurrent());
        assertEquals(1L, snapshot.failures());
        assertEquals(0, snapshot.active());
    }

    @Test
    void snapshotsCounters() {
        long start = PhaseStats.begin(PhaseStats.Phase.CHUNK_TICKS);
        PhaseStats.addWait(PhaseStats.Phase.CHUNK_TICKS, 7L);
        PhaseStats.end(PhaseStats.Phase.CHUNK_TICKS, start);

        PhaseStats.Snapshot snapshot = PhaseStats.snapshot(PhaseStats.Phase.CHUNK_TICKS);
        assertEquals(1L, snapshot.mainCalls());
        assertEquals(1, snapshot.maxConcurrent());
        assertEquals(7L, snapshot.waitNanos());
        assertEquals(0, snapshot.active());
    }
}

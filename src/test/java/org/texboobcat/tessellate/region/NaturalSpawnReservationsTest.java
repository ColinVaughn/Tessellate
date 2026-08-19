package org.texboobcat.tessellate.region;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class NaturalSpawnReservationsTest {

    @Test
    void concurrentReservationsCannotMultiplyGlobalOrLocalCaps() throws Exception {
        NaturalSpawnReservations caps = new NaturalSpawnReservations(
            new int[]{0}, new int[]{140}, new int[][]{{0}, {0}}, new int[]{70});
        ExecutorService workers = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger committed = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (int worker = 0; worker < 16; worker++) {
            futures.add(workers.submit(() -> {
                start.await();
                for (int attempt = 0; attempt < 500; attempt++) {
                    int player = attempt & 1;
                    NaturalSpawnReservations.Reservation reservation = caps.tryReserve(
                        0, new int[]{player}, () -> true, () -> { }, () -> { });
                    if (reservation != null) {
                        reservation.commit();
                        committed.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        workers.shutdownNow();

        assertEquals(140, committed.get());
        assertEquals(140, caps.globalCount(0));
        assertEquals(70, caps.localCount(0, 0));
        assertEquals(70, caps.localCount(1, 0));
        assertFalse(caps.canReserve(0, new int[]{0, 1}));
    }

    @Test
    void failedAdmissionAndRollbackLeaveNoReservationBehind() {
        NaturalSpawnReservations caps = new NaturalSpawnReservations(
            new int[]{5}, new int[]{10}, new int[][]{{3}}, new int[]{10});
        AtomicInteger extra = new AtomicInteger();

        assertNull(caps.tryReserve(0, new int[]{0}, () -> false,
            extra::incrementAndGet, extra::decrementAndGet));
        assertEquals(5, caps.globalCount(0));
        assertEquals(3, caps.localCount(0, 0));

        NaturalSpawnReservations.Reservation reservation = caps.tryReserve(
            0, new int[]{0}, () -> true, extra::incrementAndGet, extra::decrementAndGet);
        assertNotNull(reservation);
        assertEquals(1, extra.get());
        reservation.close();

        assertEquals(0, extra.get());
        assertEquals(5, caps.globalCount(0));
        assertEquals(3, caps.localCount(0, 0));
    }
}

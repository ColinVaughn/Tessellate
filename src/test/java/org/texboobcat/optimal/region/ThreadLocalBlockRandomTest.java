package org.texboobcat.optimal.region;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadLocalBlockRandomTest {

    @Test
    void preservesTheVanillaLcgOnEachThread() throws Exception {
        int initial = 123456789;
        ThreadLocalBlockRandom random = new ThreadLocalBlockRandom(initial);
        assertEquals(ThreadLocalBlockRandom.advance(initial), random.nextState());

        CountDownLatch firstDraws = new CountDownLatch(2);
        CountDownLatch secondDraws = new CountDownLatch(1);
        AtomicReference<int[]> firstResult = new AtomicReference<>();
        AtomicReference<int[]> secondResult = new AtomicReference<>();
        Thread first = thread(random, firstDraws, secondDraws, firstResult);
        Thread second = thread(random, firstDraws, secondDraws, secondResult);
        first.start();
        second.start();
        boolean bothDrew = firstDraws.await(1, TimeUnit.SECONDS);
        secondDraws.countDown();
        first.join();
        second.join();
        assertTrue(bothDrew);

        for (int[] result : new int[][]{firstResult.get(), secondResult.get()}) {
            assertEquals(ThreadLocalBlockRandom.advance(result[0]), result[1]);
        }
        assertTrue(firstResult.get()[0] != secondResult.get()[0]);
    }

    private static Thread thread(ThreadLocalBlockRandom random, CountDownLatch firstDraws,
                                 CountDownLatch secondDraws, AtomicReference<int[]> result) {
        return new Thread(() -> {
            int first = random.nextState();
            firstDraws.countDown();
            try {
                secondDraws.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            result.set(new int[]{first, random.nextState()});
        });
    }
}

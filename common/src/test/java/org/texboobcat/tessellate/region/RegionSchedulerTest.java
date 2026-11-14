package org.texboobcat.tessellate.region;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionSchedulerTest {

    private static void withScheduler(int threads, int cap, java.util.function.Consumer<RegionScheduler> body)
        throws Exception {
        RegionScheduler scheduler = new RegionScheduler(threads, cap);
        try {
            body.accept(scheduler);
        } finally {
            assertTrue(scheduler.shutdownAndAwait(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void everyTaskRunsExactlyOnceOnAPersistentWorker() throws Exception {
        withScheduler(4, 4, scheduler -> {
            AtomicInteger runs = new AtomicInteger();
            Set<String> threads = ConcurrentHashMap.newKeySet();
            List<Runnable> tasks = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                tasks.add(() -> {
                    runs.incrementAndGet();
                    threads.add(Thread.currentThread().getName());
                    assertTrue(Thread.currentThread() instanceof PhaseStats.WorkerMarker);
                });
            }
            scheduler.runAllAndWait(tasks);
            assertEquals(200, runs.get());
            assertTrue(threads.size() <= 4, "a thread per task would show here: " + threads);
            assertTrue(threads.stream().allMatch(n -> n.startsWith("tessellate-region-")));
        });
    }

    @Test
    void theConcurrencyCapIsRespected() throws Exception {
        withScheduler(6, 2, scheduler -> {
            AtomicInteger active = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            List<Runnable> tasks = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                tasks.add(() -> {
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    active.decrementAndGet();
                });
            }
            scheduler.runAllAndWait(tasks);
            assertTrue(peak.get() <= 2, "peak concurrency was " + peak.get());
            assertTrue(peak.get() >= 1);
        });
    }

    @Test
    void idleWorkersTakeWorkFromABusyOne() throws Exception {
        // One long task and many short ones: with stealing, the short ones finish on the other
        // workers while the long one is still running, so total time is about the long task.
        withScheduler(4, 4, scheduler -> {
            CountDownLatch shortDone = new CountDownLatch(30);
            List<Runnable> tasks = new ArrayList<>();
            tasks.add(() -> {
                try {
                    // Completes only after every short task has run elsewhere.
                    assertTrue(shortDone.await(5, TimeUnit.SECONDS), "short tasks were stuck behind the long one");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            for (int i = 0; i < 30; i++) {
                tasks.add(shortDone::countDown);
            }
            scheduler.runAllAndWait(tasks);
        });
    }

    @Test
    void aFailingTaskDoesNotStopTheOthersAndIsReported() throws Exception {
        withScheduler(3, 3, scheduler -> {
            AtomicInteger ran = new AtomicInteger();
            List<Runnable> tasks = new ArrayList<>();
            tasks.add(() -> {
                throw new IllegalArgumentException("boom");
            });
            for (int i = 0; i < 10; i++) {
                tasks.add(ran::incrementAndGet);
            }
            IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> scheduler.runAllAndWait(tasks));
            assertEquals("boom", thrown.getCause().getMessage());
            assertEquals(10, ran.get());
        });
    }

    @Test
    void busyTimeAccumulatesForUtilization() throws Exception {
        withScheduler(2, 2, scheduler -> {
            long before = scheduler.busyNanos();
            scheduler.runAllAndWait(List.of(() -> spin(20), () -> spin(20)));
            long busy = scheduler.busyNanos() - before;
            assertTrue(busy >= 30_000_000L, "two 20 ms tasks should record about 40 ms, got " + busy);
            double u = scheduler.utilization(before, scheduler.busyNanos(), 20_000_000L);
            assertTrue(u > 0.5 && u <= 1.0, "utilization " + u);
        });
    }

    private static void spin(long millis) {
        long end = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    @Test
    void tasksStartInSubmissionOrderWhenThereIsOneWorker() throws Exception {
        withScheduler(1, 1, scheduler -> {
            List<Integer> order = java.util.Collections.synchronizedList(new ArrayList<>());
            List<Runnable> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int id = i;
                tasks.add(() -> order.add(id));
            }
            scheduler.runAllAndWait(tasks);
            List<Integer> expected = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                expected.add(i);
            }
            assertEquals(expected, order);
        });
    }
}

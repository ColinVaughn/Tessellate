package org.texboobcat.tessellate.region;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;

/**
 * Persistent worker pool for region tasks, built on {@link ForkJoinPool}.
 *
 * <ul>
 *   <li><b>Work stealing:</b> an idle worker takes queued tasks from busy ones, so the plan does
 *       not need to guess how long each task will really take.</li>
 *   <li><b>No region/thread affinity:</b> a task is not bound to a worker, and a region is not
 *       bound to a core. Region state is protected by region ownership (the thread context
 *       entered inside the task), not by which thread runs it, so tasks may land anywhere.</li>
 *   <li><b>Priorities:</b> tasks start in submission order, so callers submit the most important
 *       and most expensive first (see {@link LoadBalancePlanner}).</li>
 *   <li><b>Concurrency cap:</b> at most {@code maxConcurrent} tasks run at once, which leaves
 *       cores free for the main thread, network and GC.</li>
 * </ul>
 *
 * <p>Worker threads are persistent and never created per region. No Minecraft dependencies.
 */
public final class RegionScheduler {

    /** Worker thread; the marker lets the rest of the mod tell region workers from the main thread. */
    public static final class Worker extends ForkJoinWorkerThread implements PhaseStats.WorkerMarker {
        private final int index;

        Worker(ForkJoinPool pool, int index, ClassLoader loader) {
            super(pool);
            this.index = index;
            this.setName("tessellate-region-" + index);
            this.setDaemon(true);
            this.setContextClassLoader(loader);
        }

        public int index() {
            return this.index;
        }
    }

    private final ForkJoinPool pool;
    private final int parallelism;
    private final Semaphore permits;
    private final AtomicLongArray busyNanos;

    public RegionScheduler(int parallelism, int maxConcurrent) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1: " + parallelism);
        }
        this.parallelism = parallelism;
        this.permits = new Semaphore(Math.max(1, Math.min(maxConcurrent, parallelism)));
        this.busyNanos = new AtomicLongArray(parallelism);
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        AtomicInteger next = new AtomicInteger();
        // asyncMode keeps each worker's own queue first-in-first-out, so submission order (the
        // priority order) is also start order, rather than the stack order of fork/join recursion.
        this.pool = new ForkJoinPool(parallelism, p -> new Worker(p, Math.floorMod(next.getAndIncrement(), parallelism), loader), null, true);
    }

    public int parallelism() {
        return this.parallelism;
    }

    public boolean isRunning() {
        return !this.pool.isShutdown();
    }

    public long stealCount() {
        return this.pool.getStealCount();
    }

    /** Total task time on workers so far; the difference between two reads over a wall interval is utilization. */
    public long busyNanos() {
        long sum = 0L;
        for (int i = 0; i < this.busyNanos.length(); i++) {
            sum += this.busyNanos.get(i);
        }
        return sum;
    }

    /** Fraction of the pool's capacity used between two reads of {@link #busyNanos()}. */
    public double utilization(long busyBefore, long busyAfter, long wallNanos) {
        return wallNanos <= 0L ? 0.0 : Math.min(1.0, (double) (busyAfter - busyBefore) / ((double) wallNanos * this.parallelism));
    }

    /** Fire and forget. {@code onFailure} receives anything the task throws. */
    public void execute(Runnable task, Consumer<Throwable> onFailure) {
        this.pool.execute(() -> runGuarded(task, onFailure));
    }

    /**
     * Runs the tasks and returns when all have finished. Tasks start in list order. If any task
     * fails the rest still run, and the first failure is thrown afterwards with the others
     * attached as suppressed.
     */
    public void runAllAndWait(List<Runnable> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(tasks.size());
        List<Throwable> failures = new ArrayList<>(0);
        for (Runnable task : tasks) {
            try {
                this.pool.execute(() -> {
                    try {
                        runGuarded(task, failure -> {
                            synchronized (failures) {
                                failures.add(failure);
                            }
                        });
                    } finally {
                        latch.countDown();
                    }
                });
            } catch (RejectedExecutionException e) {
                latch.countDown();
                synchronized (failures) {
                    failures.add(e);
                }
            }
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for region workers", e);
        }
        synchronized (failures) {
            if (!failures.isEmpty()) {
                Throwable first = failures.get(0);
                for (int i = 1; i < failures.size(); i++) {
                    first.addSuppressed(failures.get(i));
                }
                throw new IllegalStateException("region task failed on a worker thread", first);
            }
        }
    }

    private void runGuarded(Runnable task, Consumer<Throwable> onFailure) {
        Thread thread = Thread.currentThread();
        this.permits.acquireUninterruptibly();
        long start = System.nanoTime();
        try {
            task.run();
        } catch (Throwable t) {
            onFailure.accept(t);
        } finally {
            long elapsed = System.nanoTime() - start;
            this.permits.release();
            if (thread instanceof Worker worker) {
                this.busyNanos.addAndGet(worker.index(), elapsed);
            }
        }
    }

    public boolean shutdownAndAwait(long timeout, TimeUnit unit) throws InterruptedException {
        this.pool.shutdown();
        if (this.pool.awaitTermination(timeout, unit)) {
            return true;
        }
        this.pool.shutdownNow();
        return this.pool.awaitTermination(timeout, unit);
    }
}

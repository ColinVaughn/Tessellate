package org.texboobcat.optimal.region;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.texboobcat.optimal.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// The thread pool that runs region ticks.
//
// Threads are named and identifiable, because the ownership guard's whole job is to say which
// thread touched what, and "pool-3-thread-2" makes that report useless.
public final class RegionWorkers {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static ExecutorService pool;
    private static int poolSize;
    private static final TaskTracker TASKS = new TaskTracker();

    private RegionWorkers() {
    }

    // Marker type so the guard and the chunk-access fast path can identify a worker cheaply.
    public static final class WorkerThread extends Thread implements PhaseStats.WorkerMarker {
        private final int index;

        WorkerThread(Runnable target, int index) {
            super(target, "optimal-region-" + index);
            this.index = index;
            setDaemon(true);
        }

        int index() {
            return this.index;
        }
    }

    public static boolean isWorkerThread() {
        return Thread.currentThread() instanceof WorkerThread;
    }

    public static int currentWorkerIndex() {
        return Thread.currentThread() instanceof WorkerThread worker ? worker.index() : -1;
    }

    public static synchronized void start() {
        if (pool != null) {
            return;
        }
        int configured = Config.workerThreads;
        poolSize = configured > 0
            ? configured
            : Math.max(1, Runtime.getRuntime().availableProcessors() - 2);

        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> new WorkerThread(runnable, counter.getAndIncrement());
        pool = Executors.newFixedThreadPool(poolSize, factory);
        LOGGER.info("optimal: started {} region worker thread(s)", poolSize);
    }

    public static synchronized void stop() {
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOGGER.warn("optimal: region workers did not shut down within 20s");
                }
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        pool = null;
    }

    public static int poolSize() {
        return poolSize;
    }

    public static boolean isRunning() {
        return pool != null && !pool.isShutdown();
    }

    // Submits a region tick without waiting for it.
    //
    // This is what actually isolates. There is no barrier, so a region that overruns its 50 ms
    // slot simply does not get a new task until it finishes, while every other region continues to
    // be given one every tick. The staged mode below cannot do this: with a barrier, one slow
    // region holds the whole server, which is measurably worse than ticking serially.
    public static void submit(Runnable task, java.util.function.Consumer<Throwable> onFailure) {
        TASKS.started();
        try {
            pool.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    onFailure.accept(t);
                } finally {
                    TASKS.finished();
                }
            });
        } catch (RuntimeException e) {
            TASKS.finished();
            throw e;
        }
    }

    // How many region tasks are currently dispatched or running.
    public static int activeTasks() {
        return TASKS.active();
    }

    // True when a region task is in flight, so anything the main thread touches now is racing it.
    //
    // Used by the entity-section diagnostic to name the main-thread caller that mutates entity
    // storage underneath a worker, which is the concrete thing blocking async isolation.
    public static boolean anyTaskInFlight() {
        return TASKS.active() > 0;
    }

    // Waits for all independently submitted region tasks to finish. Main thread only.
    public static void awaitIdle() {
        if (isWorkerThread()) {
            throw new IllegalStateException("a region worker cannot wait for the region pool");
        }
        TASKS.awaitIdle();
    }

    // JDK-only so the wait invariant remains unit-testable without a Minecraft runtime.
    static final class TaskTracker {
        private final AtomicInteger active = new AtomicInteger();

        void started() {
            synchronized (this) {
                this.active.incrementAndGet();
            }
        }

        void finished() {
            synchronized (this) {
                if (this.active.decrementAndGet() == 0) {
                    this.notifyAll();
                }
            }
        }

        int active() {
            return this.active.get();
        }

        void awaitIdle() {
            synchronized (this) {
                while (this.active.get() > 0) {
                    try {
                        this.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted waiting for region workers", e);
                    }
                }
            }
        }
    }

    // Runs region tasks in parallel but keeps the entity-phase barrier.
    public static void runAllAndWait(List<Runnable> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        if (!isRunning() || tasks.size() == 1) {
            runSerial(tasks);
            return;
        }

        CountDownLatch latch = new CountDownLatch(tasks.size());
        List<Throwable> failures = new ArrayList<>();
        for (Runnable task : tasks) {
            submitAndCountDown(task, latch, failures);
        }

        await(latch);
        throwFailures(failures);
    }

    private static void runSerial(List<Runnable> tasks) {
        for (Runnable task : tasks) {
            task.run();
        }
    }

    private static void submitAndCountDown(Runnable task, CountDownLatch latch,
                                            List<Throwable> failures) {
        pool.execute(() -> {
            try {
                task.run();
            } catch (Throwable failure) {
                synchronized (failures) {
                    failures.add(failure);
                }
            } finally {
                latch.countDown();
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for region workers", e);
        }
    }

    private static void throwFailures(List<Throwable> failures) {
        synchronized (failures) {
            if (!failures.isEmpty()) {
                Throwable first = failures.get(0);
                for (int i = 1; i < failures.size(); i++) {
                    first.addSuppressed(failures.get(i));
                }
                throw new IllegalStateException("region tick failed on a worker thread", first);
            }
        }
    }
}

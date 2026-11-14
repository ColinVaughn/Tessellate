package org.texboobcat.tessellate.region;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.texboobcat.tessellate.Config;
import org.texboobcat.tessellate.CompatibilityReporter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// The thread pool that runs region ticks. The pool itself is RegionScheduler (work stealing, a
// concurrency cap, tasks start in submission order); this class is the mod-facing wrapper that
// adds task tracking, failure reporting and the load-balanced entry point.
//
// Threads are named and identifiable, because the ownership guard's whole job is to say which
// thread touched what, and "pool-3-thread-2" makes that report useless.
public final class RegionWorkers {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static RegionScheduler scheduler;
    private static int poolSize;
    private static final TaskTracker TASKS = new TaskTracker();

    private RegionWorkers() {
    }

    public static boolean isWorkerThread() {
        return Thread.currentThread() instanceof RegionScheduler.Worker;
    }

    public static int currentWorkerIndex() {
        return Thread.currentThread() instanceof RegionScheduler.Worker worker ? worker.index() : -1;
    }

    public static synchronized void start() {
        if (scheduler != null) {
            return;
        }
        int configured = Config.workerThreads;
        poolSize = configured > 0
            ? configured
            : Math.max(1, Runtime.getRuntime().availableProcessors() - 2);

        // Optional cap on tasks running at once, to keep cores free for the main thread, network and
        // GC. Defaults to the pool size, i.e. no cap beyond the number of workers.
        int maxConcurrent = Integer.getInteger("tessellate.maxConcurrentRegionTasks", poolSize);
        scheduler = new RegionScheduler(poolSize, maxConcurrent);
        LOGGER.info("tessellate: started {} region worker thread(s), at most {} running at once",
            poolSize, Math.max(1, Math.min(maxConcurrent, poolSize)));
    }

    public static synchronized void stop() {
        if (scheduler == null) {
            return;
        }
        try {
            if (!scheduler.shutdownAndAwait(10, TimeUnit.SECONDS)) {
                LOGGER.warn("tessellate: region workers did not shut down within 20s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        scheduler = null;
    }

    public static int poolSize() {
        return poolSize;
    }

    public static boolean isRunning() {
        return scheduler != null && scheduler.isRunning();
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
            scheduler.execute(() -> {
                try {
                    task.run();
                } finally {
                    TASKS.finished();
                }
            }, onFailure);
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
        try {
            scheduler.runAllAndWait(tasks);
        } catch (IllegalStateException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                throw e; // interrupted while waiting
            }
            CompatibilityReporter.report("region-worker", "worker-failure",
                "region tick failed on a worker thread", cause);
            throw new IllegalStateException("region tick failed on a worker thread", cause);
        }
    }

    // One region's work, tagged with the region so the planner can see its measured cost.
    public record PlannedTask(Region region, Runnable work) {
    }

    // Like runAllAndWait, but groups regions into tasks by measured load first: a heavy region gets
    // a task to itself and starts first, many light ones share a task. Regions in one task run one
    // after another on a single worker; every region still runs exactly once, and a failure in one
    // does not stop the others in its task. Topology is untouched, so regions stay as isolated as
    // they were: this only changes how work is handed to threads.
    public static void runPlannedAndWait(LoadBalancePlanner planner, List<PlannedTask> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        if (!isRunning() || tasks.size() == 1) {
            List<Runnable> serial = new ArrayList<>(tasks.size());
            for (PlannedTask task : tasks) {
                serial.add(task.work());
            }
            runSerial(serial);
            return;
        }

        List<LoadBalancePlanner.Item> items = new ArrayList<>(tasks.size());
        Map<Integer, Runnable> byRegion = new HashMap<>();
        for (PlannedTask task : tasks) {
            Region region = task.region();
            RegionLoad load = region.load();
            // A region with no samples yet (just created) falls back to the throttle's estimate.
            long cost = load.samples() > 0 ? load.cost() : region.costNanos();
            items.add(new LoadBalancePlanner.Item(region.id(), cost));
            byRegion.put(region.id(), task.work());
        }

        LoadBalancePlanner.Plan plan = planner.plan(items, Math.max(1, poolSize));
        List<Runnable> grouped = new ArrayList<>(plan.groups().size());
        for (LoadBalancePlanner.Group group : plan.groups()) {
            List<Runnable> members = new ArrayList<>(group.regionIds().size());
            for (int regionId : group.regionIds()) {
                members.add(byRegion.get(regionId));
            }
            grouped.add(members.size() == 1 ? members.get(0) : sequentially(members));
        }
        runAllAndWait(grouped);
    }

    private static Runnable sequentially(List<Runnable> members) {
        return () -> {
            Throwable first = null;
            for (Runnable member : members) {
                try {
                    member.run();
                } catch (Throwable t) {
                    if (first == null) {
                        first = t;
                    } else {
                        first.addSuppressed(t);
                    }
                }
            }
            if (first instanceof RuntimeException runtime) {
                throw runtime;
            } else if (first instanceof Error error) {
                throw error;
            } else if (first != null) {
                throw new IllegalStateException(first);
            }
        };
    }

    private static void runSerial(List<Runnable> tasks) {
        for (Runnable task : tasks) {
            task.run();
        }
    }
}

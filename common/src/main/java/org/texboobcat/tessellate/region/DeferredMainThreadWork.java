package org.texboobcat.tessellate.region;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

// Replays worker writes to level-global state on the main thread. Global compatibility tasks wait
// for all region work; region-scoped tasks wait only for their owner.
public final class DeferredMainThreadWork {

    private record Deferred(MainThreadBoundaries.Boundary boundary, String levelKey,
                            int regionId, String source, Runnable work, boolean global) {
        boolean ready() {
            return (!this.global || !RegionWorkers.anyTaskInFlight())
                && (this.levelKey == null || RegionTracker.regionIdle(this.levelKey, this.regionId));
        }
    }

    private static final Queue<Deferred> PENDING = new ConcurrentLinkedQueue<>();

    // Incremented by every worker, so it must be atomic; see DeferredEntityCallbacks.
    private static final java.util.concurrent.atomic.LongAdder DEFERRED =
        new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.AtomicInteger OUTSTANDING =
        new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger PEAK_OUTSTANDING =
        new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger GLOBAL_OUTSTANDING =
        new java.util.concurrent.atomic.AtomicInteger();

    // Main thread only.
    private static long replayed;

    private DeferredMainThreadWork() {
    }

    public static void defer(Runnable work) {
        defer(MainThreadBoundaries.Boundary.CROSS_REGION_WRITES, work);
    }

    public static void defer(MainThreadBoundaries.Boundary boundary, Runnable work) {
        defer(boundary, work, false);
    }

    public static void deferGlobal(MainThreadBoundaries.Boundary boundary, Runnable work) {
        defer(boundary, work, true);
    }

    private static void defer(MainThreadBoundaries.Boundary boundary, Runnable work,
                              boolean global) {
        org.texboobcat.tessellate.guard.RegionThreadContext.Binding binding =
            org.texboobcat.tessellate.guard.RegionThreadContext.currentBinding();
        String levelKey = binding == null ? null : binding.levelKey();
        int regionId = binding == null ? -1 : binding.region().id();
        enqueue(boundary, levelKey, regionId,
            binding == null ? "global/main"
                : MainThreadBoundaries.source(levelKey, regionId), work, global);
    }

    public static void deferForRegion(String levelKey, int regionId, Runnable work) {
        deferForRegion(MainThreadBoundaries.Boundary.CROSS_REGION_WRITES,
            levelKey, regionId, work);
    }

    public static void deferForRegion(MainThreadBoundaries.Boundary boundary, String levelKey,
                                      int regionId, Runnable work) {
        enqueue(boundary, levelKey, regionId,
            MainThreadBoundaries.source(levelKey, regionId), work, false);
    }

    private static void enqueue(MainThreadBoundaries.Boundary boundary, String levelKey,
                                int regionId, String source, Runnable work, boolean global) {
        int outstanding = OUTSTANDING.incrementAndGet();
        PEAK_OUTSTANDING.accumulateAndGet(outstanding, Math::max);
        PENDING.add(new Deferred(boundary, levelKey, regionId, source, work, global));
        if (global) {
            GLOBAL_OUTSTANDING.incrementAndGet();
        }
        DEFERRED.increment();
        MainThreadBoundaries.queued(boundary, source);
    }

    public static long deferredCount() {
        return DEFERRED.sum();
    }

    public static long replayedCount() {
        return replayed;
    }

    public static int pendingCount() {
        return OUTSTANDING.get();
    }

    public static int peakPendingCount() {
        return PEAK_OUTSTANDING.get();
    }

    // Replays everything queued. Main thread only.
    //
    // Drained into a local queue first, so work that defers again cannot spin here forever.
    public static void drain() {
        if (PENDING.isEmpty()) {
            return;
        }
        if (GLOBAL_OUTSTANDING.get() > 0 && !RegionWorkers.isWorkerThread()
            && RegionWorkers.anyTaskInFlight()) {
            RegionWorkers.awaitIdle();
        }
        Queue<Deferred> batch = new ArrayDeque<>();
        Deferred item;
        while ((item = PENDING.poll()) != null) {
            batch.add(item);
        }
        while ((item = batch.poll()) != null) {
            if (!item.ready()) {
                PENDING.add(item);
                continue;
            }
            try {
                Deferred replay = item;
                MainThreadBoundaries.replay(item.boundary(), item.source(),
                    () -> PhaseStats.measure(PhaseStats.Phase.DEFERRED_COMMITS,
                        replay.work()));
                replayed++;
            } finally {
                OUTSTANDING.decrementAndGet();
                if (item.global()) {
                    GLOBAL_OUTSTANDING.decrementAndGet();
                }
            }
        }
    }

    public static void reset() {
        PENDING.clear();
        DEFERRED.reset();
        replayed = 0;
        OUTSTANDING.set(0);
        PEAK_OUTSTANDING.set(0);
        GLOBAL_OUTSTANDING.set(0);
    }
}

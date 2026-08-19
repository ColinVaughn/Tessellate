package org.texboobcat.optimal.region;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

// Work a region worker tried to do to level-global state, replayed on the main thread.
//
// Companion to DeferredEntityCallbacks, for the writes that do not arrive through the
// entity callbacks. Ticking an entity can schedule a block tick by stepping on farmland or
// displacing water. It can also raise a block event. Cross-owner scheduled ticks and block events
// are handed to their destination region through this boundary.
//
// Deliberately global rather than per level. The queued work already knows which level it
// belongs to, because it captures the container it was called on, so a per-level queue would only
// add a lookup on the path that defers. Draining is main-thread-only and happens at the head of
// every level tick, so at most one tick of latency is introduced.
//
// Latency is the visible cost: a block tick scheduled from a worker starts counting down one
// tick later than it would have. That is the same order of imprecision the regional tick model
// already introduces, and far cheaper than the alternative of making both containers concurrent.
public final class DeferredMainThreadWork {

    private record Deferred(MainThreadBoundaries.Boundary boundary, String levelKey,
                            int regionId, String source, Runnable work) {
        boolean ready() {
            return this.levelKey == null || RegionTracker.regionIdle(this.levelKey, this.regionId);
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

    // Main thread only.
    private static long replayed;

    private DeferredMainThreadWork() {
    }

    public static void defer(Runnable work) {
        defer(MainThreadBoundaries.Boundary.CROSS_REGION_WRITES, work);
    }

    public static void defer(MainThreadBoundaries.Boundary boundary, Runnable work) {
        org.texboobcat.optimal.guard.RegionThreadContext.Binding binding =
            org.texboobcat.optimal.guard.RegionThreadContext.currentBinding();
        String levelKey = binding == null ? null : binding.levelKey();
        int regionId = binding == null ? -1 : binding.region().id();
        enqueue(boundary, levelKey, regionId,
            binding == null ? "global/main"
                : MainThreadBoundaries.source(levelKey, regionId), work);
    }

    public static void deferForRegion(String levelKey, int regionId, Runnable work) {
        deferForRegion(MainThreadBoundaries.Boundary.CROSS_REGION_WRITES,
            levelKey, regionId, work);
    }

    public static void deferForRegion(MainThreadBoundaries.Boundary boundary, String levelKey,
                                      int regionId, Runnable work) {
        enqueue(boundary, levelKey, regionId,
            MainThreadBoundaries.source(levelKey, regionId), work);
    }

    private static void enqueue(MainThreadBoundaries.Boundary boundary, String levelKey,
                                int regionId, String source, Runnable work) {
        int outstanding = OUTSTANDING.incrementAndGet();
        PEAK_OUTSTANDING.accumulateAndGet(outstanding, Math::max);
        PENDING.add(new Deferred(boundary, levelKey, regionId, source, work));
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
            }
        }
    }

    public static void reset() {
        PENDING.clear();
        DEFERRED.reset();
        replayed = 0;
        OUTSTANDING.set(0);
        PEAK_OUTSTANDING.set(0);
    }
}

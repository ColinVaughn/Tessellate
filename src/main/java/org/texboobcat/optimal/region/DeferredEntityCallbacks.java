package org.texboobcat.optimal.region;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.LevelCallback;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;

// Entity lifecycle callbacks raised on a worker, replayed on the main thread.
//
// Why this exists
//
// ServerLevel$EntityCallbacks is the single place where an entity moving, appearing or
// disappearing updates level-global state. Between onTrackingStart and
// onTrackingEnd alone it writes ChunkMap.entityMap, the level's player list,
// navigatingMobs, dragonParts, the dynamic game-event listeners, and posts to the
// NeoForge event bus. None of those are per-region and none are thread-safe.
//
// Every one of them is reached whenever an entity crosses a chunk section, which is a routine
// part of ticking a mob. With regions on worker threads, all of that runs concurrently, and
// the failure is not a tidy exception at the point of the race. It corrupts a fastutil open hash
// map, and the corruption surfaces later on the main thread as
// NullPointerException: "this.wrapped" is null inside ChunkMap.tick, which names
// nothing that caused it.
//
// Rather than making six unrelated containers concurrent, the callbacks themselves move: a
// worker queues them, and the main thread replays them in order at the start of the next level
// tick, before anything reads what they update. One invariant replaces six.
//
// What it costs
//
// Lifecycle updates lag by up to one tick. An entity that crosses a section boundary on a
// worker is tracked from the following tick. Vanilla already batches tracking updates into
// ChunkMap.tick, so this is a delay in bookkeeping rather than a change in what players
// see, and ordering per entity is preserved because the queue is FIFO.
public final class DeferredEntityCallbacks {

    // Which callback was deferred. Replay dispatches back through the level's own callbacks.
    public enum Kind {
        CREATED,
        DESTROYED,
        TICKING_START,
        TICKING_END,
        TRACKING_START,
        TRACKING_END,
        SECTION_CHANGE
    }

    private record Deferred(Kind kind, Entity entity, String source) {
    }

    private final Queue<Deferred> pending = new ConcurrentLinkedQueue<>();

    // Reused so draining a busy tick allocates nothing.
    private final Queue<Deferred> draining = new ArrayDeque<>();

    // Incremented by every worker, so it must be atomic.
    //
    // A plain long here reported more callbacks replayed than deferred, because concurrent
    // increments were lost. Such a counter misses the concurrency it exists to observe.
    private final java.util.concurrent.atomic.LongAdder deferredCount =
        new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.AtomicInteger outstanding =
        new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger peakOutstanding =
        new java.util.concurrent.atomic.AtomicInteger();

    // Main thread only, so a plain field is enough.
    private long replayedCount;

    public void defer(Kind kind, Entity entity) {
        int pending = this.outstanding.incrementAndGet();
        this.peakOutstanding.accumulateAndGet(pending, Math::max);
        String source = MainThreadBoundaries.currentSource();
        this.pending.add(new Deferred(kind, entity, source));
        this.deferredCount.increment();
        MainThreadBoundaries.queued(MainThreadBoundaries.Boundary.ENTITY_LIFECYCLE, source);
    }

    public boolean isEmpty() {
        return this.pending.isEmpty();
    }

    public long deferredCount() {
        return this.deferredCount.sum();
    }

    public long replayedCount() {
        return this.replayedCount;
    }

    public int pendingCount() {
        return this.outstanding.get();
    }

    public int peakPendingCount() {
        return this.peakOutstanding.get();
    }

    // Replays every queued callback on the calling thread, which must be the main thread.
    //
    // Drained into a local queue first so a callback that defers again cannot loop forever.
    public void drain(LevelCallback<Entity> callbacks, Predicate<Entity> ready) {
        if (this.pending.isEmpty()) {
            return;
        }
        Deferred item;
        while ((item = this.pending.poll()) != null) {
            this.draining.add(item);
        }
        while ((item = this.draining.poll()) != null) {
            if (!ready.test(item.entity())) {
                this.pending.add(item);
                continue;
            }
            Deferred replay = item;
            try {
                MainThreadBoundaries.replay(
                    MainThreadBoundaries.Boundary.ENTITY_LIFECYCLE, replay.source(),
                    () -> PhaseStats.measure(PhaseStats.Phase.DEFERRED_COMMITS,
                        () -> this.replay(callbacks, replay)));
                this.replayedCount++;
            } finally {
                this.outstanding.decrementAndGet();
            }
        }
    }

    private void replay(LevelCallback<Entity> callbacks, Deferred item) {
        switch (item.kind()) {
            case CREATED -> callbacks.onCreated(item.entity());
            case DESTROYED -> callbacks.onDestroyed(item.entity());
            case TICKING_START -> callbacks.onTickingStart(item.entity());
            case TICKING_END -> callbacks.onTickingEnd(item.entity());
            case TRACKING_START -> callbacks.onTrackingStart(item.entity());
            case TRACKING_END -> callbacks.onTrackingEnd(item.entity());
            case SECTION_CHANGE -> callbacks.onSectionChange(item.entity());
        }
    }
}

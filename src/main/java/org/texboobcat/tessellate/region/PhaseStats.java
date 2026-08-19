package org.texboobcat.tessellate.region;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

// Cumulative proof of where regional work actually ran and whether it overlapped.
public final class PhaseStats {

    interface WorkerMarker {
    }

    public enum Phase {
        SCHEDULED_TICKS("scheduled"),
        SCHEDULED_BLOCK_TICKS("scheduled-blocks"),
        SCHEDULED_FLUID_TICKS("scheduled-fluids"),
        CHUNK_TICKS("chunks"),
        NATURAL_SPAWNING("spawning"),
        BLOCK_EVENTS("block-events"),
        BLOCK_EVENT_PACKETS("block-event-packets"),
        ENTITIES("entities"),
        BLOCK_ENTITIES("block-entities"),
        PATHFINDING("pathfinding"),
        DEFERRED_COMMITS("deferred-commits");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    public record Snapshot(Phase phase, long workerCalls, long mainCalls,
                           long workerNanos, long mainNanos, long waitNanos,
                           int active, int maxConcurrent, long failures) {
    }

    private static final Counter[] COUNTERS = Arrays.stream(Phase.values())
        .map(ignored -> new Counter())
        .toArray(Counter[]::new);

    private PhaseStats() {
    }

    public static long begin(Phase phase) {
        Counter counter = counter(phase);
        if (Thread.currentThread() instanceof WorkerMarker) {
            counter.workerCalls.increment();
        } else {
            counter.mainCalls.increment();
        }
        int active = counter.active.incrementAndGet();
        counter.maxConcurrent.accumulateAndGet(active, Math::max);
        return System.nanoTime();
    }

    public static void end(Phase phase, long startedNanos) {
        Counter counter = counter(phase);
        long elapsed = Math.max(0L, System.nanoTime() - startedNanos);
        if (Thread.currentThread() instanceof WorkerMarker) {
            counter.workerNanos.add(elapsed);
        } else {
            counter.mainNanos.add(elapsed);
        }
        counter.active.decrementAndGet();
    }

    public static void measure(Phase phase, Runnable work) {
        long start = begin(phase);
        try {
            work.run();
        } catch (Throwable failure) {
            failed(phase);
            throw failure;
        } finally {
            end(phase, start);
        }
    }

    public static void addWait(Phase phase, long nanos) {
        counter(phase).waitNanos.add(Math.max(0L, nanos));
    }

    public static void failed(Phase phase) {
        counter(phase).failures.increment();
    }

    public static Snapshot snapshot(Phase phase) {
        Counter counter = counter(phase);
        return new Snapshot(phase, counter.workerCalls.sum(), counter.mainCalls.sum(),
            counter.workerNanos.sum(), counter.mainNanos.sum(), counter.waitNanos.sum(),
            counter.active.get(), counter.maxConcurrent.get(), counter.failures.sum());
    }

    public static List<Snapshot> snapshots() {
        return Arrays.stream(Phase.values()).map(PhaseStats::snapshot).toList();
    }

    public static void reset() {
        for (Counter counter : COUNTERS) {
            counter.reset();
        }
    }

    private static Counter counter(Phase phase) {
        return COUNTERS[phase.ordinal()];
    }

    private static final class Counter {
        private final LongAdder workerCalls = new LongAdder();
        private final LongAdder mainCalls = new LongAdder();
        private final LongAdder workerNanos = new LongAdder();
        private final LongAdder mainNanos = new LongAdder();
        private final LongAdder waitNanos = new LongAdder();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();
        private final LongAdder failures = new LongAdder();

        private void reset() {
            this.workerCalls.reset();
            this.mainCalls.reset();
            this.workerNanos.reset();
            this.mainNanos.reset();
            this.waitNanos.reset();
            this.active.set(0);
            this.maxConcurrent.set(0);
            this.failures.reset();
        }
    }
}

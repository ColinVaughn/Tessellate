package org.texboobcat.tessellate.region;

import org.texboobcat.tessellate.guard.RegionThreadContext;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

// Stable, bounded accounting for work intentionally committed on the main thread.
public final class MainThreadBoundaries {

    public enum Boundary {
        ENTITY_LIFECYCLE("entity-lifecycle"),
        TELEPORT_DIMENSION("teleport-dimension"),
        CROSS_REGION_WRITES("cross-region-writes"),
        BLOCK_ENTITY_REGISTRATION("block-entity-registration"),
        NATURAL_SPAWN_COMMITS("natural-spawn-commits"),
        CHUNK_PLAYER_BROADCASTS("chunk-player-broadcasts"),
        CUSTOM_SPAWNERS("custom-spawners"),
        MOD_COMPATIBILITY("mod-compatibility"),
        COMMAND_BARRIER("command-barrier"),
        SAVE_BARRIER("save-barrier"),
        SHUTDOWN_BARRIER("shutdown-barrier"),
        TOPOLOGY_BARRIER("topology-barrier");

        private final String label;

        Boundary(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    public record Snapshot(Boundary boundary, long queued, long replayed, int pending,
                           long directCalls, long mainNanos, long queuedFailures,
                           long directFailures, String lastSource) {

        public boolean balanced() {
            return this.queued == this.replayed + this.pending + this.queuedFailures;
        }
    }

    private static final Counter[] COUNTERS = Arrays.stream(Boundary.values())
        .map(ignored -> new Counter())
        .toArray(Counter[]::new);
    private static final ThreadLocal<Boundary> REPLAYING = new ThreadLocal<>();

    private MainThreadBoundaries() {
    }

    public static String currentSource() {
        RegionThreadContext.Binding binding = RegionThreadContext.currentBinding();
        return binding == null ? "global/main"
            : source(binding.levelKey(), binding.region().id());
    }

    public static String source(String levelKey, int regionId) {
        return levelKey + (regionId < 0 ? "#unowned" : "#region" + regionId);
    }

    public static String globalSource(String levelKey) {
        return levelKey + "#global";
    }

    public static void queued(Boundary boundary, String source) {
        Counter counter = counter(boundary);
        counter.queued.increment();
        counter.pending.incrementAndGet();
        counter.lastSource.set(source);
    }

    public static void replay(Boundary boundary, String source, Runnable work) {
        Counter counter = counter(boundary);
        long start = System.nanoTime();
        Boundary previous = REPLAYING.get();
        REPLAYING.set(boundary);
        try {
            work.run();
            counter.replayed.increment();
        } catch (Throwable failure) {
            counter.queuedFailures.increment();
            throw failure;
        } finally {
            if (previous == null) {
                REPLAYING.remove();
            } else {
                REPLAYING.set(previous);
            }
            counter.mainNanos.add(Math.max(0L, System.nanoTime() - start));
            counter.pending.decrementAndGet();
            counter.lastSource.set(source);
        }
    }

    public static void measure(Boundary boundary, String source, Runnable work) {
        if (REPLAYING.get() == boundary) {
            work.run();
            return;
        }
        Counter counter = counter(boundary);
        long start = System.nanoTime();
        try {
            work.run();
            counter.directCalls.increment();
        } catch (Throwable failure) {
            counter.directFailures.increment();
            throw failure;
        } finally {
            counter.mainNanos.add(Math.max(0L, System.nanoTime() - start));
            counter.lastSource.set(source);
        }
    }

    public static void measure(Boundary boundary, Runnable work) {
        measure(boundary, currentSource(), work);
    }

    public static <T> T call(Boundary boundary, String source, Supplier<T> work) {
        Counter counter = counter(boundary);
        long start = System.nanoTime();
        try {
            T result = work.get();
            counter.directCalls.increment();
            return result;
        } catch (Throwable failure) {
            counter.directFailures.increment();
            throw failure;
        } finally {
            counter.mainNanos.add(Math.max(0L, System.nanoTime() - start));
            counter.lastSource.set(source);
        }
    }

    public static Snapshot snapshot(Boundary boundary) {
        Counter counter = counter(boundary);
        return new Snapshot(boundary, counter.queued.sum(), counter.replayed.sum(),
            counter.pending.get(), counter.directCalls.sum(), counter.mainNanos.sum(),
            counter.queuedFailures.sum(), counter.directFailures.sum(),
            counter.lastSource.get());
    }

    public static List<Snapshot> snapshots() {
        return Arrays.stream(Boundary.values()).map(MainThreadBoundaries::snapshot).toList();
    }

    public static void reset() {
        for (Counter counter : COUNTERS) {
            counter.reset();
        }
        REPLAYING.remove();
    }

    private static Counter counter(Boundary boundary) {
        return COUNTERS[boundary.ordinal()];
    }

    private static final class Counter {
        private final LongAdder queued = new LongAdder();
        private final LongAdder replayed = new LongAdder();
        private final AtomicInteger pending = new AtomicInteger();
        private final LongAdder directCalls = new LongAdder();
        private final LongAdder mainNanos = new LongAdder();
        private final LongAdder queuedFailures = new LongAdder();
        private final LongAdder directFailures = new LongAdder();
        private final AtomicReference<String> lastSource =
            new AtomicReference<>("none");

        private void reset() {
            this.queued.reset();
            this.replayed.reset();
            this.pending.set(0);
            this.directCalls.reset();
            this.mainNanos.reset();
            this.queuedFailures.reset();
            this.directFailures.reset();
            this.lastSource.set("none");
        }
    }
}

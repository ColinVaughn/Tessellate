package org.texboobcat.tessellate.region;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Per-region CPU load, measured with {@link System#nanoTime()} and smoothed with an exponential
 * moving average so a one-tick spike nudges the estimate instead of swinging it.
 *
 * <p>Phase code adds time into the current tick's raw counters while the region's task runs
 * ({@link #add}, {@link #time}). The main thread then calls {@link #endTick} once per server tick,
 * which folds the raw counters into the averages and clears them. The raw counters are atomic
 * because a region's phases may run on different worker threads within one tick; the
 * averages are only touched from the main thread.
 *
 * <p>This class has no Minecraft dependencies on purpose, so the policy built on top of it can be
 * unit-tested without a running game.
 */
public final class RegionLoad {

    /** What is being measured. The first five are times in nanoseconds. */
    public enum Metric {
        /** Wall time of the whole region task (all phases, including ones not listed below). */
        TICK,
        BLOCK_ENTITY,
        ENTITY,
        /** Scheduled block/fluid ticks and other queue drains. */
        SCHEDULED,
        /** Task queue: messages waiting for this region when its tick began. A count, not a time. */
        QUEUE,
        /** Share of one worker's tick budget this region used (0..1+). Derived in {@link #endTick}. */
        CPU
    }

    private static final int TIME_METRICS = 4; // TICK, BLOCK_ENTITY, ENTITY, SCHEDULED
    private static final int N = Metric.values().length;

    private final double alpha;
    private final AtomicLongArray raw = new AtomicLongArray(N);
    private final AtomicIntegerArray rawCounts = new AtomicIntegerArray(2); // 0 = entities, 1 = block entities
    private final double[] ema = new double[N];
    private double emaNanosPerEntity;
    private double emaNanosPerBlockEntity;
    private int entityCount;
    private int blockEntityCount;
    private long samples;

    public RegionLoad() {
        this(0.2);
    }

    /** @param alpha weight of the newest sample, in (0, 1]. Smaller reacts slower and is steadier. */
    public RegionLoad(double alpha) {
        if (!(alpha > 0.0 && alpha <= 1.0)) {
            throw new IllegalArgumentException("alpha must be in (0, 1]: " + alpha);
        }
        this.alpha = alpha;
    }

    // ---- raw collection (any thread) -------------------------------------------------------

    public void add(Metric metric, long amount) {
        if (amount > 0L) {
            this.raw.addAndGet(metric.ordinal(), amount);
        }
    }

    /** Adds {@code System.nanoTime() - startNanos} to a time metric. */
    public void time(Metric metric, long startNanos) {
        add(metric, System.nanoTime() - startNanos);
    }

    /** Adds messages this region drained from the task queue this tick. */
    public void addQueued(int count) {
        add(Metric.QUEUE, count);
    }

    public void setQueueDepth(int depth) {
        this.raw.set(Metric.QUEUE.ordinal(), Math.max(0, depth));
    }

    /** Records how many active entities / block entities this region processed this tick. */
    public void setCounts(int entities, int blockEntities) {
        this.rawCounts.set(0, Math.max(0, entities));
        this.rawCounts.set(1, Math.max(0, blockEntities));
    }

    // ---- folding (main thread) --------------------------------------------------------------

    /**
     * Folds this tick's raw counters into the averages and clears them.
     *
     * @param tickWallNanos wall time of the whole server tick, used for the CPU share
     * @param divisor       tick divisor the region ran with; a throttled region only ran a
     *                      1/divisor slice, so times are scaled back up to a whole-region estimate
     */
    public void endTick(long tickWallNanos, int divisor) {
        int scale = Math.max(1, divisor);
        double[] sample = new double[N];
        for (int i = 0; i < TIME_METRICS; i++) {
            sample[i] = (double) this.raw.getAndSet(i, 0L) * scale;
        }
        // A region whose task time was never recorded still has the sum of its parts.
        double parts = sample[Metric.BLOCK_ENTITY.ordinal()] + sample[Metric.ENTITY.ordinal()]
            + sample[Metric.SCHEDULED.ordinal()];
        sample[Metric.TICK.ordinal()] = Math.max(sample[Metric.TICK.ordinal()], parts);
        sample[Metric.QUEUE.ordinal()] = this.raw.getAndSet(Metric.QUEUE.ordinal(), 0L);
        sample[Metric.CPU.ordinal()] = tickWallNanos > 0L ? sample[Metric.TICK.ordinal()] / tickWallNanos : 0.0;

        int entities = this.rawCounts.get(0);
        int blockEntities = this.rawCounts.get(1);
        if (this.samples == 0) {
            System.arraycopy(sample, 0, this.ema, 0, N);
        } else {
            for (int i = 0; i < N; i++) {
                this.ema[i] += this.alpha * (sample[i] - this.ema[i]);
            }
        }
        this.entityCount = entities;
        this.blockEntityCount = blockEntities;
        // A tick with no recorded time for a phase carries no information about its per-item cost
        // (the phase may simply not have run), so it must not drag the learned average toward zero.
        if (entities > 0 && sample[Metric.ENTITY.ordinal()] > 0.0) {
            this.emaNanosPerEntity = blend(this.emaNanosPerEntity, sample[Metric.ENTITY.ordinal()] / entities);
        }
        if (blockEntities > 0 && sample[Metric.BLOCK_ENTITY.ordinal()] > 0.0) {
            this.emaNanosPerBlockEntity = blend(this.emaNanosPerBlockEntity,
                sample[Metric.BLOCK_ENTITY.ordinal()] / blockEntities);
        }
        this.samples++;
    }

    private double blend(double current, double observed) {
        return current == 0.0 ? observed : current + this.alpha * (observed - current);
    }

    // ---- reading (main thread) ---------------------------------------------------------------

    public double average(Metric metric) {
        return this.ema[metric.ordinal()];
    }

    public int entityCount() {
        return this.entityCount;
    }

    public int blockEntityCount() {
        return this.blockEntityCount;
    }

    public long samples() {
        return this.samples;
    }

    /**
     * Smoothed cost of one full tick of this region. This is what scheduling and throttling decide
     * from. Once learned, the per-entity and per-block-entity averages let it extrapolate when the
     * counts change faster than the time average can follow, which is also how a brand-new region
     * (right after a merge or split) gets a sensible first estimate instead of zero.
     */
    public long cost() {
        return Math.round(this.ema[Metric.TICK.ordinal()]);
    }

    /** What a tick should cost given current counts and the learned per-item costs. */
    public long predictedCost() {
        double perItem = this.emaNanosPerEntity * this.entityCount
            + this.emaNanosPerBlockEntity * this.blockEntityCount;
        return Math.round(perItem + this.ema[Metric.SCHEDULED.ordinal()]);
    }

    // ---- topology changes --------------------------------------------------------------------

    /**
     * Seeds a freshly created region from the one it came from, so that a merge or split does not
     * restart its cost at zero. A zero would make the new region look idle for several ticks, and
     * the balancer would immediately re-batch it, which is exactly the boundary churn to avoid.
     *
     * @param share fraction of the source's load this region inherits, in [0, 1]
     */
    public void seedFrom(RegionLoad source, double share) {
        double f = Math.max(0.0, Math.min(1.0, share));
        for (int i = 0; i < N; i++) {
            this.ema[i] = source.ema[i] * f;
        }
        this.emaNanosPerEntity = source.emaNanosPerEntity;
        this.emaNanosPerBlockEntity = source.emaNanosPerBlockEntity;
        this.entityCount = (int) Math.round(source.entityCount * f);
        this.blockEntityCount = (int) Math.round(source.blockEntityCount * f);
        this.samples = Math.min(source.samples, 1);
    }

    /**
     * Adds another region's load to this one, for the survivor of a merge. The survivor keeps its own
     * history and takes on the absorbed region's; per-item costs are averaged by item count.
     */
    public void absorb(RegionLoad other) {
        for (int i = 0; i < N; i++) {
            this.ema[i] += other.ema[i];
        }
        this.emaNanosPerEntity = weighted(this.emaNanosPerEntity, this.entityCount,
            other.emaNanosPerEntity, other.entityCount);
        this.emaNanosPerBlockEntity = weighted(this.emaNanosPerBlockEntity, this.blockEntityCount,
            other.emaNanosPerBlockEntity, other.blockEntityCount);
        this.entityCount += other.entityCount;
        this.blockEntityCount += other.blockEntityCount;
        this.samples = Math.max(this.samples, Math.min(other.samples, 1));
    }

    /** Seeds this region as the union of several regions that were just merged into it. */
    public void seedFromMerged(Collection<RegionLoad> sources) {
        for (RegionLoad source : sources) {
            absorb(source);
        }
    }

    /** Keeps a fraction of the load; the other part went to regions split off from this one. */
    public void scale(double share) {
        double f = Math.max(0.0, Math.min(1.0, share));
        for (int i = 0; i < N; i++) {
            this.ema[i] *= f;
        }
        this.entityCount = (int) Math.round(this.entityCount * f);
        this.blockEntityCount = (int) Math.round(this.blockEntityCount * f);
    }

    private static double weighted(double a, int weightA, double b, int weightB) {
        int total = weightA + weightB;
        if (total <= 0) {
            return Math.max(a, b);
        }
        return (a * weightA + b * weightB) / total;
    }

    /** Null-safe: records messages a region drained (the unowned bucket has no region). */
    public static void addQueued(Region region, int count) {
        if (region != null) {
            region.load().addQueued(count);
        }
    }

    /**
     * Divides the original region's load over itself and the regions split off from it, by section
     * share. Sections have already moved: {@code original} holds only what it kept.
     */
    public static void divideOnSplit(Region original, java.util.List<Region> splitOff) {
        int total = original.sectionCount();
        for (Region fresh : splitOff) {
            total += fresh.sectionCount();
        }
        int divisor = Math.max(1, total);
        for (Region fresh : splitOff) {
            fresh.load().seedFrom(original.load(), (double) fresh.sectionCount() / divisor);
        }
        original.load().scale((double) original.sectionCount() / divisor);
    }

    /** Null-safe: records the entity phase of a region that ticked only entities (the throttled path). */
    public static void recordEntityPhase(Region region, long startNanos, int entityCount) {
        if (region != null) {
            region.load().time(Metric.ENTITY, startNanos);
            region.load().setCounts(entityCount, 0);
        }
    }
}

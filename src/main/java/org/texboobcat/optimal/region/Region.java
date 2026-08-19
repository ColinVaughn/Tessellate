package org.texboobcat.optimal.region;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

// A connected component of entity-ticking sections.
//
// A region records its sections, tick state, timing, and latest worker thread.
//
// Contains no Minecraft types.
public final class Region {

    private final int id;
    private final int sectionShift;
    private final LongSet sections = new LongOpenHashSet();
    private final LongSet sectionsView = LongSets.unmodifiable(this.sections);

    private volatile long lastTickNanos;
    private volatile long lastBlockEntityNanos;
    private volatile int lastWorkerIndex = -1;
    private volatile String lastThreadName = "not run";

    // Tick every divisor-th server tick. 1 means every tick.
    //
    // This is the isolation mechanism. A region whose work does not fit in the server's budget
    // is ticked less often rather than being allowed to push the whole tick over 50 ms, so it runs
    // in slow motion while every other region keeps its full rate. Set by RegionThrottle
    // from measured cost, once per level tick, before any subsystem runs.
    private volatile int tickDivisor = 1;

    // Offset within the divisor cycle, so throttled regions do not all land on the same tick.
    //
    // Without it, several regions throttled to 1/4 would every fourth tick all fire together
    // and reproduce the very spike the throttle exists to remove.
    private volatile int phase;

    // Cost estimate: scheduled/chunk/entity work plus block-entity work for one full tick.
    private volatile long costNanos;

    public Region(int id, int sectionShift) {
        this.id = id;
        this.sectionShift = sectionShift;
    }

    public int id() {
        return this.id;
    }

    public int sectionCount() {
        return this.sections.size();
    }

    // Live, read-only view. The regionizer owns mutation.
    public LongSet sections() {
        return this.sectionsView;
    }

    void addSection(long section) {
        this.sections.add(section);
    }

    void removeSection(long section) {
        this.sections.remove(section);
    }

    void addAllSections(LongSet other) {
        this.sections.addAll(other);
    }

    public boolean ownsSection(long section) {
        return this.sections.contains(section);
    }

    public boolean ownsChunk(int chunkX, int chunkZ) {
        return this.sections.contains(RegionSectionPos.fromChunk(chunkX, chunkZ, this.sectionShift));
    }

    public boolean ownsChunkLong(long chunkPos) {
        return this.sections.contains(RegionSectionPos.fromChunkLong(chunkPos, this.sectionShift));
    }

    public void recordTick(long nanos) {
        this.lastTickNanos = nanos;
    }

    public long lastTickNanos() {
        return this.lastTickNanos;
    }

    // Kept separate from the entity figure so the two costs stay distinguishable.
    public void recordBlockEntityTick(long nanos) {
        this.lastBlockEntityNanos = nanos;
    }

    public long lastBlockEntityNanos() {
        return this.lastBlockEntityNanos;
    }

    public void recordExecutionThread(String threadName, int workerIndex) {
        this.lastThreadName = threadName;
        this.lastWorkerIndex = workerIndex;
    }

    public String lastThreadName() {
        return this.lastThreadName;
    }

    public int lastWorkerIndex() {
        return this.lastWorkerIndex;
    }

    // ---- throttling ---------------------------------------------------------------------------

    public int tickDivisor() {
        return this.tickDivisor;
    }

    public void setTickDivisor(int divisor) {
        if (divisor != this.tickDivisor) {
            this.tickDivisor = divisor;
            // Re-derive the phase from the id so regions with the same divisor spread across the
            // cycle deterministically, rather than all firing on the same tick.
            this.phase = divisor <= 1 ? 0 : Math.floorMod(this.id * 2654435761L, divisor);
        }
    }

    // Which slice of this region's work runs on this tick.
    //
    // A throttled region ticks a 1/divisor share of its members every tick rather than
    // all of them every divisor-th tick. Each member still advances at exactly
    // 20/divisor TPS at the same slow rate, but the cost is spread evenly instead of
    // arriving as one spike.
    //
    // Returns the slice index, or -1 when the region is unthrottled and everything runs
    public int sliceOn(long serverTick) {
        return this.tickDivisor <= 1 ? -1 : Math.floorMod(serverTick + this.phase, this.tickDivisor);
    }

    // Whether a member with this stable key belongs to the slice running on this tick.
    public boolean memberInSlice(int memberKey, int slice) {
        return slice < 0 || Math.floorMod(memberKey, this.tickDivisor) == slice;
    }

    public int phase() {
        return this.phase;
    }

    // Measured cost of one full tick of this region, used to choose the divisor.
    public long costNanos() {
        return this.costNanos;
    }

    public void updateCost(long entityNanos, long blockEntityNanos) {
        long observed = entityNanos + blockEntityNanos;
        // Exponential moving average: a single expensive tick should nudge the divisor, not swing
        // it, or the throttle oscillates and the region visibly stutters.
        this.costNanos = this.costNanos == 0 ? observed : (this.costNanos * 3 + observed) / 4;
    }

    @Override
    public String toString() {
        return "Region#" + this.id + "{sections=" + this.sections.size() + "}";
    }
}

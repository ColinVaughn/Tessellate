package org.texboobcat.optimal.region;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.ArrayList;
import java.util.List;

// The per-region half of the level's tick state.
//
// This splits out the entity tick list, the largest cost in the benchmark and the one
// that makes EntityTickList unusable concurrently. Its forEach throws
// "Only one concurrent iteration supported". Block-entity tickers, scheduled ticks, block events
// and the level random follow the same pattern.
//
// Kept out of Region so the regionizer core stays free of Minecraft types and remains
// unit-testable with no game context. LevelRegionIndex owns the mapping from region id to
// this state.
//
// Insertion order is preserved, matching vanilla's Int2ObjectLinkedOpenHashMap, so that
// entity tick order within a region is unchanged from vanilla.
public final class RegionTickState {

    private final int regionId;
    private final Int2ObjectLinkedOpenHashMap<Entity> entities = new Int2ObjectLinkedOpenHashMap<>();

    // Reused each tick so that iterating a region allocates nothing.
    private final List<Entity> snapshot = new ArrayList<>();

    // This region's block-entity tickers for the current tick.
    //
    // Unlike entities, membership is rebuilt each tick from the level's own list rather than
    // maintained incrementally. The level list stays the single source of truth, so the debug
    // dumps that read it keep working and nothing has to be kept in sync. Block entities never
    // move, so the only churn is creation and removal.
    private final List<TickingBlockEntity> blockEntityBuffer = new ArrayList<>();
    private volatile int reportedBlockEntityCount;

    public RegionTickState(int regionId) {
        this.regionId = regionId;
    }

    public int regionId() {
        return this.regionId;
    }

    public int entityCount() {
        return this.entities.size();
    }

    public boolean addEntity(Entity entity) {
        return this.entities.put(entity.getId(), entity) == null;
    }

    public boolean removeEntity(Entity entity) {
        return this.entities.remove(entity.getId()) != null;
    }

    public boolean containsEntity(Entity entity) {
        return this.entities.containsKey(entity.getId());
    }

    public Iterable<Int2ObjectMap.Entry<Entity>> entries() {
        return this.entities.int2ObjectEntrySet();
    }

    // Moves everything from other into this state. Used when regions merge.
    public void absorb(RegionTickState other) {
        for (Int2ObjectMap.Entry<Entity> entry : other.entities.int2ObjectEntrySet()) {
            this.entities.put(entry.getIntKey(), entry.getValue());
        }
        other.entities.clear();
    }

    // Copies the current members into the reusable snapshot buffer and returns it.
    //
    // Every region is snapshotted before any region is ticked, so an entity that crosses a
    // region boundary partway through the entity phase is ticked exactly once rather than twice
    // or not at all.
    public List<Entity> snapshot() {
        this.snapshot.clear();
        if (!this.entities.isEmpty()) {
            this.snapshot.addAll(this.entities.values());
        }
        return this.snapshot;
    }

    public List<Entity> currentSnapshot() {
        return this.snapshot;
    }

    public void clearSnapshot() {
        this.snapshot.clear();
    }

    // ---- block entity tickers ---------------------------------------------------------------

    // True while a worker is still ticking this region's snapshot.
    //
    // This is the whole isolation mechanism. In async mode the main thread submits a region's
    // tick and does not wait; if that region is still busy when the next server tick comes round,
    // it is simply skipped. A region that takes 150 ms therefore ticks at about 6 TPS while its
    // neighbors keep getting a fresh task every 50 ms. Nobody waits for the slow one.
    //
    // It also guards the snapshot buffer: the main thread must not re-snapshot a region whose
    // worker is still reading the previous snapshot, and must not mutate the entity map underneath
    // it. Both are covered by only ever touching a region that is not in flight.
    private final java.util.concurrent.atomic.AtomicBoolean inFlight =
        new java.util.concurrent.atomic.AtomicBoolean();

    public boolean isInFlight() {
        return this.inFlight.get();
    }

    // Returns true if this call claimed the region; false if a worker already holds it.
    public boolean tryClaim() {
        return this.inFlight.compareAndSet(false, true);
    }

    public void release() {
        this.inFlight.set(false);
    }

    // Blocks until no worker holds this region.
    //
    // This is how the main thread touches region-owned state safely without a global barrier.
    // It waits for one region, not for all of them, and only for the remainder of that region's
    // current slice. Throttling keeps a slice small, so an overloaded region does not translate
    // into a long main-thread stall the way a barrier would.
    //
    // Spins briefly before yielding: the common case is that the region is already idle, or is
    // microseconds from finishing, and parking would cost more than the wait.
    public void awaitIdle() {
        if (!this.inFlight.get()) {
            return;
        }
        for (int spin = 0; spin < 256; spin++) {
            if (!this.inFlight.get()) {
                return;
            }
            Thread.onSpinWait();
        }
        while (this.inFlight.get()) {
            java.util.concurrent.locks.LockSupport.parkNanos(50_000L);
        }
    }

    public void clearBlockEntityBuffer() {
        this.blockEntityBuffer.clear();
        this.reportedBlockEntityCount = 0;
    }

    public void bufferBlockEntity(TickingBlockEntity ticker) {
        this.blockEntityBuffer.add(ticker);
        this.reportedBlockEntityCount = this.blockEntityBuffer.size();
    }

    public List<TickingBlockEntity> blockEntityBuffer() {
        return this.blockEntityBuffer;
    }

    // Reused so a throttled region's slice costs no allocation.
    private final List<TickingBlockEntity> blockEntitySlice = new ArrayList<>();

    // The share of this region's block entities that runs on this tick.
    //
    // Keyed on the block position rather than list order, so a given hopper ticks on the same
    // slice every cycle instead of drifting between slices as the list is rebuilt.
    public List<TickingBlockEntity> sliceBlockEntities(Region region, int slice) {
        this.blockEntitySlice.clear();
        for (TickingBlockEntity ticker : this.blockEntityBuffer) {
            BlockPos pos = ticker.getPos();
            int key = pos == null ? 0 : pos.hashCode();
            if (region.memberInSlice(key, slice)) {
                this.blockEntitySlice.add(ticker);
            }
        }
        return this.blockEntitySlice;
    }

    public int blockEntityCount() {
        return this.reportedBlockEntityCount;
    }

    public void reportBlockEntityCount(int count) {
        this.reportedBlockEntityCount = count;
    }

}

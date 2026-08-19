package org.texboobcat.tessellate.region;

import net.minecraft.world.level.block.entity.TickingBlockEntity;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

// Walks block-entity tickers region by region, timing each region as it goes.
//
// Handed to vanilla's Level.tickBlockEntities loop in place of the flat list iterator,
// so the removal check, shouldTickBlocksAt gate, and tick() call stay unchanged.
// Only the order changes.
//
// A region's timer closes when the walk moves past its last ticker, so cost is attributed
// without the caller needing to know regions exist.
final class RegionOrderedTickers implements Iterator<TickingBlockEntity> {

    // The level's own list, which remains the source of truth for membership.
    private final List<TickingBlockEntity> backing;
    private final List<Region> regions;
    private final List<List<TickingBlockEntity>> buffers;

    private int bufferIndex = -1;
    private int withinBuffer;
    private long regionStartNanos;
    @Nullable
    private TickingBlockEntity last;

    RegionOrderedTickers(List<TickingBlockEntity> backing,
                         List<Region> regions,
                         List<List<TickingBlockEntity>> buffers) {
        this.backing = backing;
        this.regions = regions;
        this.buffers = buffers;
        advanceToNonEmptyBuffer();
    }

    private void advanceToNonEmptyBuffer() {
        closeCurrentRegion();
        this.bufferIndex++;
        this.withinBuffer = 0;
        while (this.bufferIndex < this.buffers.size() && this.buffers.get(this.bufferIndex).isEmpty()) {
            this.bufferIndex++;
        }
        if (this.bufferIndex < this.buffers.size()) {
            this.regionStartNanos = System.nanoTime();
        }
    }

    private void closeCurrentRegion() {
        if (this.bufferIndex < 0 || this.bufferIndex >= this.buffers.size()) {
            return;
        }
        Region region = this.regions.get(this.bufferIndex);
        if (region != null) {
            region.recordBlockEntityTick(System.nanoTime() - this.regionStartNanos);
        }
    }

    @Override
    public boolean hasNext() {
        while (this.bufferIndex < this.buffers.size()
            && this.withinBuffer >= this.buffers.get(this.bufferIndex).size()) {
            advanceToNonEmptyBuffer();
        }
        return this.bufferIndex < this.buffers.size();
    }

    @Override
    public TickingBlockEntity next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        this.last = this.buffers.get(this.bufferIndex).get(this.withinBuffer++);
        return this.last;
    }

    // Vanilla calls this for tickers whose block entity has been removed.
    //
    // The buffers are rebuilt from scratch each tick, so only the level's list needs the
    // removal. It is a linear scan, but it runs only for a block entity destroyed part-way
    // through the very tick that is iterating it, which is rare.
    @Override
    public void remove() {
        if (this.last == null) {
            throw new IllegalStateException("remove() before next()");
        }
        this.backing.remove(this.last);
        this.last = null;
    }
}

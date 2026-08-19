package org.texboobcat.optimal.region;

import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import org.texboobcat.optimal.mixin.WalkNodeEvaluatorInvoker;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

// Vanilla's direct-mapped path cache with immutable, atomically-published entries.
public final class ConcurrentPathTypeCache extends PathTypeCache {

    private static final int SIZE = 4096;
    private static final int MASK = SIZE - 1;

    private record Entry(long position, long version, PathType pathType) {
    }

    private final AtomicReferenceArray<Entry> entries = new AtomicReferenceArray<>(SIZE);
    private final AtomicLongArray versions = new AtomicLongArray(SIZE);

    @Override
    public PathType getOrCompute(BlockGetter level, BlockPos pos) {
        long packed = pos.asLong();
        int index = index(packed);
        long version = this.versions.get(index);
        Entry cached = this.entries.get(index);
        if (cached != null && cached.position() == packed && cached.version() == version) {
            return cached.pathType();
        }

        PathType computed = WalkNodeEvaluatorInvoker.optimal$getPathTypeFromState(level, pos);
        if (this.versions.get(index) == version) {
            this.entries.set(index, new Entry(packed, version, computed));
        }
        return computed;
    }

    @Override
    public void invalidate(BlockPos pos) {
        int index = index(pos.asLong());
        this.versions.incrementAndGet(index);
        this.entries.set(index, null);
    }

    private static int index(long position) {
        return (int) HashCommon.mix(position) & MASK;
    }
}

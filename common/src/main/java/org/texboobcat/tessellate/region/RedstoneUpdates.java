package org.texboobcat.tessellate.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.texboobcat.tessellate.guard.RegionThreadContext;

import java.lang.ref.WeakReference;

public final class RedstoneUpdates {
    private record CheckedArea(WeakReference<Level> level, RegionThreadContext.Binding binding,
                               int minX, int maxX, int minZ, int maxZ) {
        boolean contains(Level world, RegionThreadContext.Binding task,
                         int x0, int x1, int z0, int z1) {
            return this.binding == task && this.level.get() == world
                && x0 >= this.minX && x1 <= this.maxX && z0 >= this.minZ && z1 <= this.maxZ;
        }
    }

    // Only owned FULL chunks are cached: unload and scheduler rehoming wait for their worker.
    // Binding identity changes on every task, even when a worker runs the same region again.
    // One rectangle per worker; expand only if profiling shows alternating-area misses.
    private static final ThreadLocal<CheckedArea> CHECKED = new ThreadLocal<>();

    private RedstoneUpdates() { }

    // A batch moves one block; rails search eight more and inspect the far rail's neighbors.
    // A missing chunk needs vanilla's main-thread load, not an aborted or discarded update.
    public static boolean canRun(Level level, BlockPos pos) {
        return canRun(level, pos, 10);
    }

    public static boolean canRun(Level level, BlockPos pos, int radius) {
        if (!RegionWorkers.isWorkerThread()) {
            return true;
        }
        var binding = RegionThreadContext.currentBinding();
        if (binding == null) {
            return false;
        }
        int minX = (pos.getX() - radius) >> 4;
        int maxX = (pos.getX() + radius) >> 4;
        int minZ = (pos.getZ() - radius) >> 4;
        int maxZ = (pos.getZ() + radius) >> 4;
        CheckedArea checked = CHECKED.get();
        if (checked != null && checked.contains(level, binding, minX, maxX, minZ, maxZ)) {
            return true;
        }
        if (!(level.getBlockTicks() instanceof RegionalLevelTicks<?> ticks)
            || !level.dimension().location().toString().equals(binding.levelKey())) {
            return false;
        }
        CheckedArea area = new CheckedArea(new WeakReference<>(level), binding, minX, maxX, minZ, maxZ);
        if (!checkChunks(level, area, ticks)) {
            return false;
        }
        CHECKED.set(area);
        return true;
    }

    private static boolean checkChunks(Level level, CheckedArea area, RegionalLevelTicks<?> ticks) {
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                if (!ticks.isOwnedBy(ChunkPos.asLong(x, z), area.binding().region())
                    || level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    public static void defer(Runnable update) {
        DeferredMainThreadWork.deferGlobal(MainThreadBoundaries.Boundary.CROSS_REGION_WRITES, update);
    }
}

package org.texboobcat.tessellate.region;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.NeighborUpdater;

// CollectingNeighborUpdater keeps mutable traversal state, so each worker needs its own instance.
public final class ThreadLocalNeighborUpdater implements NeighborUpdater {

    private final ThreadLocal<CollectingNeighborUpdater> perThread;

    public ThreadLocalNeighborUpdater(Level level, int maxChainedNeighborUpdates) {
        this.perThread = ThreadLocal.withInitial(
            () -> new CollectingNeighborUpdater(level, maxChainedNeighborUpdates));
    }

    private CollectingNeighborUpdater current() {
        return this.perThread.get();
    }

    @Override
    public void shapeUpdate(Direction direction, BlockState state, BlockPos pos,
                            BlockPos neighborPos, int flags, int recursionLevel) {
        this.current().shapeUpdate(direction, state, pos, neighborPos, flags, recursionLevel);
    }

    @Override
    public void neighborChanged(BlockPos pos, Block block, BlockPos neighborPos) {
        this.current().neighborChanged(pos, block, neighborPos);
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block block, BlockPos neighborPos,
                                boolean movedByPiston) {
        this.current().neighborChanged(state, pos, block, neighborPos, movedByPiston);
    }
}

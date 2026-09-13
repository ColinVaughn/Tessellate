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

    private final Level level;
    private final ThreadLocal<CollectingNeighborUpdater> perThread;

    public ThreadLocalNeighborUpdater(Level level, int maxChainedNeighborUpdates) {
        this.level = level;
        this.perThread = ThreadLocal.withInitial(
            () -> new CollectingNeighborUpdater(level, maxChainedNeighborUpdates));
    }

    private CollectingNeighborUpdater current() {
        return this.perThread.get();
    }

    @Override
    public void shapeUpdate(Direction direction, BlockState state, BlockPos pos,
                            BlockPos neighborPos, int flags, int recursionLevel) {
        if (!RedstoneUpdates.canRun(this.level, pos)) {
            BlockPos target = pos.immutable();
            BlockPos source = neighborPos.immutable();
            RedstoneUpdates.defer(() -> shapeUpdate(direction, state, target, source, flags, recursionLevel));
            return;
        }
        this.current().shapeUpdate(direction, state, pos, neighborPos, flags, recursionLevel);
    }

    @Override
    public void neighborChanged(BlockPos pos, Block block, BlockPos neighborPos) {
        if (!RedstoneUpdates.canRun(this.level, pos)) {
            BlockPos target = pos.immutable();
            BlockPos source = neighborPos.immutable();
            RedstoneUpdates.defer(() -> neighborChanged(target, block, source));
            return;
        }
        this.current().neighborChanged(pos, block, neighborPos);
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block block, BlockPos neighborPos,
                                boolean movedByPiston) {
        if (!RedstoneUpdates.canRun(this.level, pos)) {
            BlockPos target = pos.immutable();
            BlockPos source = neighborPos.immutable();
            RedstoneUpdates.defer(() -> neighborChanged(state, target, block, source, movedByPiston));
            return;
        }
        this.current().neighborChanged(state, pos, block, neighborPos, movedByPiston);
    }

    @Override
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, Direction facing) {
        if (!RedstoneUpdates.canRun(this.level, pos)) {
            BlockPos source = pos.immutable();
            RedstoneUpdates.defer(() -> updateNeighborsAtExceptFromFacing(source, block, facing));
            return;
        }
        // Keep vanilla's single queued batch and recursion budget, including on the main thread.
        this.current().updateNeighborsAtExceptFromFacing(pos, block, facing);
    }
}

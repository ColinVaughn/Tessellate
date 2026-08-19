package org.texboobcat.optimal.region;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.NeighborUpdater;

// A per-thread CollectingNeighborUpdater.
//
// Vanilla keeps one per level, and it is not a stateless helper: it holds a stack of pending
// updates, a list of updates added by the current layer, and a recursion counter. Those exist to
// turn recursive neighbor updates into an iterative walk, so a redstone chain cannot overflow the
// Java stack.
//
// An entity tick reaches it when a mob steps on a pressure plate and calls
// checkPressed, which updates its neighbors. With regions on worker threads, two workers
// push onto the same stack and the walk dereferences an entry another thread already popped:
// NullPointerException inside CollectingNeighborUpdater.runUpdates, reported as
// "Colliding entity with block", naming the mob rather than the shared state.
//
// Giving each thread its own updater is the same fix already applied to Level.random,
// and it is the right shape rather than a workaround: the pending-update stack is scratch space for
// one in-progress walk, not shared world state. A region's updates are driven to completion on the
// thread that raised them, exactly as vanilla drives them on the main thread.
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

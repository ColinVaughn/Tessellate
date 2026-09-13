package org.texboobcat.tessellate.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.texboobcat.tessellate.guard.RegionThreadContext;
import org.texboobcat.tessellate.region.LevelRegionIndex;
import org.texboobcat.tessellate.region.MainThreadBoundaries;
import org.texboobcat.tessellate.region.RedstoneUpdates;
import org.texboobcat.tessellate.region.RegionalLevelTicks;
import org.texboobcat.tessellate.region.DeferredMainThreadWork;
import org.texboobcat.tessellate.region.RegionTracker;
import org.texboobcat.tessellate.region.RegionWorkers;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class RedstoneGameTestCases {
    private RedstoneGameTestCases() { }

    public static void loadedUnownedUpdates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegionTracker.quiesceAndDrain();
        LevelRegionIndex index = RegionTracker.index(level);
        BlockPos source = new BlockPos(32063, helper.absolutePos(BlockPos.ZERO).getY() + 5, 32008);
        BlockPos target = source.east();
        for (int x = 2002; x <= 2005; x++) {
            for (int z = 1999; z <= 2001; z++) level.getChunk(x, z);
        }
        var chunk = level.getChunkAt(target);
        chunk.getSection(chunk.getSectionIndex(target.getY())).setBlockState(
            target.getX() & 15, target.getY() & 15, target.getZ() & 15,
            Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST));
        chunk.setBlockState(target.below(), Blocks.STONE.defaultBlockState(), false);
        level.getChunkAt(source).setBlockState(source, Blocks.REDSTONE_BLOCK.defaultBlockState(), false);
        index.onChunkStatusChange(new ChunkPos(source).toLong(), true);
        index.tick();
        var ticks = (RegionalLevelTicks<Block>) level.getBlockTicks();
        ticks.reconcile(index);
        helper.assertTrue(index.regionForChunk(new ChunkPos(source)) != null, "source has no owner");
        helper.assertTrue(index.regionForChunk(new ChunkPos(target)) == null, "target must be unowned");
        long misses = RegionTracker.unavailableChunks();
        worker(level, source, () -> {
            helper.assertTrue(!RedstoneUpdates.canRun(level, target), "FULL unowned chunk passed preflight");
            level.neighborChanged(target, Blocks.REDSTONE_BLOCK, source);
            level.neighborChanged(level.getBlockState(target), target, Blocks.REDSTONE_BLOCK, source, false);
            level.updateNeighborsAt(source, Blocks.REDSTONE_BLOCK);
            level.updateNeighborsAtExceptFromFacing(source, Blocks.REDSTONE_BLOCK, Direction.WEST);
        });
        RegionTracker.quiesceAndDrain();
        helper.assertTrue(ticks.hasScheduledTick(target, Blocks.REPEATER), "unowned repeater update was lost");
        tick(level, target);
        helper.assertTrue(level.getBlockState(target).getValue(RepeaterBlock.POWERED), "repeater did not power");
        level.getChunkAt(source).setBlockState(source, Blocks.AIR.defaultBlockState(), false);
        worker(level, source, () -> level.neighborChanged(target, Blocks.REDSTONE_BLOCK, source));
        RegionTracker.quiesceAndDrain();
        tick(level, target);
        helper.assertTrue(!level.getBlockState(target).getValue(RepeaterBlock.POWERED), "repeater remained powered");
        checkPreflightRebinding(helper, level, source, ticks);
        helper.assertTrue(RegionTracker.unavailableChunks() == misses && RegionTracker.parallelAllowed(),
            "loaded unowned redstone caused fallback");
        index.onChunkStatusChange(new ChunkPos(source).toLong(), false);
        helper.succeed();
    }

    private static void checkPreflightRebinding(GameTestHelper helper, ServerLevel level, BlockPos pos,
                                               RegionalLevelTicks<Block> ticks) {
        var chunkTicks = (net.minecraft.world.ticks.LevelChunkTicks<Block>) level.getChunkAt(pos).getBlockTicks();
        worker(level, pos, () -> {
            helper.assertTrue(RedstoneUpdates.canRun(level, pos, 0), "owned chunk failed preflight");
            helper.assertTrue(RedstoneUpdates.canRun(level, pos, 0), "cached chunk failed preflight");
            var binding = RegionThreadContext.currentBinding();
            RegionThreadContext.exit();
            // A new FULL container starts unowned even when the index already owns its cell.
            ticks.addContainer(new ChunkPos(pos), chunkTicks);
            RegionThreadContext.enter(binding.region(), binding.levelKey());
            helper.assertTrue(!RedstoneUpdates.canRun(level, pos, 0), "preflight cache survived task rebinding");
        });
        ticks.reconcile(RegionTracker.index(level));
    }

    public static void torchBurnoutStaysLocal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        BlockPos first = fixture(level, origin, 384);
        BlockPos second = fixture(level, origin, 512);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(fixtureOwned(level, first) && fixtureOwned(level, second), "waiting for torch owners");
        }).thenExecute(() -> {
            RegionTracker.quiesceAndDrain();
            long pending = DeferredMainThreadWork.pendingCount();
            worker(level, first, () -> tick(level, first.offset(2, 0, 8)));
            helper.assertTrue(DeferredMainThreadWork.pendingCount() == pending,
                "an idle local torch queued main-thread work");
            var boundary = MainThreadBoundaries.Boundary.CHUNK_PLAYER_BROADCASTS;
            var packets = MainThreadBoundaries.snapshot(boundary);
            CountDownLatch ready = new CountDownLatch(2);
            RegionWorkers.runAllAndWait(List.of(
                () -> inRegion(level, first, () -> burnOut(helper, level, first, ready)),
                () -> inRegion(level, second, () -> burnOut(helper, level, second, ready))));
            helper.assertTrue(MainThreadBoundaries.snapshot(boundary).queued() == packets.queued() + 2,
                "torch burnout packets escaped their main-thread boundary");
            RegionTracker.quiesceAndDrain();
            helper.assertTrue(MainThreadBoundaries.snapshot(boundary).replayed() == packets.replayed() + 2,
                "torch burnout packets were not replayed");
        }).thenExecuteAfter(61, () -> {
            for (BlockPos base : List.of(first, second)) {
                worker(level, base, () -> tick(level, base.offset(2, 0, 8)));
                helper.assertTrue(level.getBlockState(base.offset(2, 0, 8)).getValue(BlockStateProperties.LIT),
                    "expired torch burnout history did not clear");
                release(level, base);
            }
            helper.assertTrue(RegionTracker.parallelAllowed(), "torch ticking caused fallback");
        }).thenSucceed();
    }

    private static void burnOut(GameTestHelper helper, ServerLevel level, BlockPos base, CountDownLatch ready) {
        await(ready);
        BlockPos torch = base.offset(2, 0, 8);
        helper.assertTrue(RedstoneUpdates.canRun(level, torch), "torch neighborhood is not worker-owned");
        for (int toggle = 0; toggle < 8; toggle++) {
            level.setBlock(torch.below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            tick(level, torch);
            helper.assertTrue(!level.getBlockState(torch).getValue(BlockStateProperties.LIT),
                "torch turn-off escaped its worker: " + torch + ", toggle=" + toggle
                    + ", support=" + level.getBlockState(torch.below())
                    + ", signal=" + level.hasSignal(torch.below(), Direction.DOWN));
            level.setBlock(torch.below(), Blocks.STONE.defaultBlockState(), 3);
            tick(level, torch);
            helper.assertTrue(level.getBlockState(torch).getValue(BlockStateProperties.LIT) == (toggle < 7),
                "torch burnout did not preserve the eight-toggle threshold");
        }
    }

    public static void unloadedUpdates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        BlockPos repeater = new BlockPos(((origin.getX() >> 4) + 1024) * 16 + 15,
            origin.getY() + 3, (origin.getZ() >> 4) * 16 + 8);
        var chunk = level.getChunkAt(repeater);
        chunk.setBlockState(repeater.below(), Blocks.STONE.defaultBlockState(), false);
        chunk.setBlockState(repeater.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), false);
        chunk.getSection(chunk.getSectionIndex(repeater.getY())).setBlockState(
            repeater.getX() & 15, repeater.getY() & 15, repeater.getZ() & 15,
            Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST));
        BlockPos railStart = repeater.west(9).south(4);
        var section = chunk.getSection(chunk.getSectionIndex(railStart.getY()));
        for (int x = 0; x <= 9; x++) {
            BlockPos rail = railStart.east(x);
            chunk.setBlockState(rail.below(), Blocks.STONE.defaultBlockState(), false);
            section.setBlockState(rail.getX() & 15, rail.getY() & 15, rail.getZ() & 15,
                Blocks.POWERED_RAIL.defaultBlockState().setValue(PoweredRailBlock.SHAPE, RailShape.EAST_WEST));
        }
        chunk.setBlockState(railStart.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), false);
        BlockPos target = repeater.east();
        long misses = RegionTracker.unavailableChunks();
        RegionWorkers.runAllAndWait(List.of(() -> {
            helper.assertTrue(level.getChunkSource().getChunk(target.getX() >> 4,
                target.getZ() >> 4, ChunkStatus.FULL, false) == null, "boundary chunk was already loaded");
            level.neighborChanged(target, Blocks.REPEATER, repeater);
            level.neighborChanged(Blocks.AIR.defaultBlockState(), target, Blocks.REPEATER, repeater, false);
            level.updateNeighborsAt(target, Blocks.REPEATER);
            level.updateNeighborsAtExceptFromFacing(target, Blocks.REPEATER, Direction.WEST);
            level.neighborShapeChanged(Direction.WEST, level.getBlockState(repeater), target, repeater, 3, 8);
            tick(level, repeater);
            level.neighborChanged(railStart, Blocks.REDSTONE_BLOCK, railStart.west());
        }, () -> { }));
        helper.assertTrue(!level.getBlockState(repeater).getValue(RepeaterBlock.POWERED),
            "boundary repeater tick did not defer");
        RegionTracker.quiesceAndDrain();
        helper.assertTrue(level.getBlockState(repeater).getValue(RepeaterBlock.POWERED),
            "deferred repeater tick was lost");
        helper.assertTrue(level.getBlockState(railStart.east(8)).getValue(PoweredRailBlock.POWERED)
            && !level.getBlockState(railStart.east(9)).getValue(PoweredRailBlock.POWERED),
            "boundary powered-rail propagation did not preserve its eight-block limit");
        level.setBlock(repeater.west(), Blocks.AIR.defaultBlockState(), 3);
        RegionWorkers.runAllAndWait(List.of(() -> tick(level, repeater), () -> { }));
        RegionTracker.quiesceAndDrain();
        helper.assertTrue(!level.getBlockState(repeater).getValue(RepeaterBlock.POWERED),
            "boundary repeater remained stuck powered");
        helper.assertTrue(RegionTracker.unavailableChunks() == misses && RegionTracker.parallelAllowed(),
            "redstone boundary caused serial fallback");
        helper.succeed();
    }

    public static void circuitsAndConcurrentSignals(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        BlockPos serial = fixture(level, origin, 128);
        BlockPos parallel = fixture(level, origin, 256);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(fixtureOwned(level, serial) && fixtureOwned(level, parallel),
                "waiting for redstone region owners");
        }).thenExecute(() -> {
            long misses = RegionTracker.unavailableChunks();
            for (int cycle = 0; cycle < 4; cycle++) {
                for (boolean powered : new boolean[]{true, false}) {
                    RegionTracker.quiesceAndDrain();
                    inRegion(level, serial, () -> drive(level, serial, powered));
                    worker(level, parallel, () -> drive(level, parallel, powered));
                    RegionTracker.quiesceAndDrain();
                    finishPiston(level, serial);
                    finishPiston(level, parallel);
                    assertCircuit(helper, level, serial, powered);
                    assertCircuit(helper, level, parallel, powered);
                }
            }
            comparatorAndObserver(helper, level, parallel);
            signalIsolation(helper, level, serial, parallel);
            helper.assertTrue(RegionTracker.unavailableChunks() == misses && RegionTracker.parallelAllowed(),
                "loaded redstone circuits caused serial fallback");
            release(level, serial);
            release(level, parallel);
        }).thenSucceed();
    }

    private static boolean fixtureOwned(ServerLevel level, BlockPos base) {
        var index = RegionTracker.index(level);
        var center = new ChunkPos(base);
        var owner = index.regionForChunk(center);
        if (owner == null) return false;
        var ticks = (RegionalLevelTicks<?>) level.getBlockTicks();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (!ticks.isOwnedBy(ChunkPos.asLong(center.x + x, center.z + z), owner)) return false;
            }
        }
        return true;
    }

    private static BlockPos fixture(ServerLevel level, BlockPos origin, int offset) {
        BlockPos base = new BlockPos(((origin.getX() >> 4) + offset) * 16 + 2,
            origin.getY() + 4, (origin.getZ() >> 4) * 16 + 2);
        ChunkPos center = new ChunkPos(base);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                level.setChunkForced(center.x + x, center.z + z, true);
                level.getChunk(center.x + x, center.z + z);
            }
        }
        for (int x = 0; x <= 12; x++) {
            for (int z = 0; z <= 10; z++) {
                level.setBlock(base.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(base.offset(x, 0, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        for (int x = 1; x <= 7; x++) {
            level.setBlock(base.east(x), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        }
        for (int x = 1; x <= 8; x++) {
            level.setBlock(base.offset(x, 0, 10), Blocks.POWERED_RAIL.defaultBlockState()
                .setValue(PoweredRailBlock.SHAPE, RailShape.EAST_WEST), 3);
        }
        level.setBlock(base.east(8), Blocks.REPEATER.defaultBlockState()
            .setValue(RepeaterBlock.FACING, Direction.WEST), 3);
        level.setBlock(base.east(9), Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
        level.setBlock(base.offset(1, 0, 4), Blocks.WATER_CAULDRON.defaultBlockState()
            .setValue(LayeredCauldronBlock.LEVEL, 3), 3);
        level.setBlock(base.offset(2, 0, 4), Blocks.COMPARATOR.defaultBlockState()
            .setValue(ComparatorBlock.FACING, Direction.WEST), 3);
        level.setBlock(base.offset(2, 0, 8), Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
        level.setBlock(base.offset(6, 0, 8), Blocks.STICKY_PISTON.defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST), 3);
        level.setBlock(base.offset(7, 0, 8), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(base.offset(10, 0, 4), Blocks.OBSERVER.defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.WEST), 3);
        return base;
    }

    private static void drive(ServerLevel level, BlockPos base, boolean powered) {
        BlockState input = (powered ? Blocks.REDSTONE_BLOCK : Blocks.AIR).defaultBlockState();
        level.setBlock(base, input, 3);
        level.setBlock(base.south(10), input, 3);
        level.setBlock(base.offset(2, -1, 8),
            (powered ? Blocks.REDSTONE_BLOCK : Blocks.STONE).defaultBlockState(), 3);
        level.setBlock(base.offset(5, 0, 8), input, 3);
        tick(level, base.east(8));
        tick(level, base.east(9));
        tick(level, base.offset(2, 0, 4));
        tick(level, base.offset(2, 0, 8));
        LevelRegionIndex index = RegionTracker.index(level);
        index.blockEvents().drainCurrent();
    }

    private static void finishPiston(ServerLevel level, BlockPos base) {
        for (int frame = 0; frame < 3; frame++) {
            for (int x = 6; x <= 8; x++) {
                BlockPos pos = base.offset(x, 0, 8);
                if (level.getBlockEntity(pos) instanceof PistonMovingBlockEntity moving) {
                    PistonMovingBlockEntity.tick(level, pos, level.getBlockState(pos), moving);
                }
            }
        }
    }

    private static void assertCircuit(GameTestHelper helper, ServerLevel level, BlockPos base, boolean on) {
        for (int x = 1; x <= 7; x++) {
            helper.assertTrue(level.getBlockState(base.east(x)).getValue(RedStoneWireBlock.POWER)
                == (on ? 16 - x : 0), "dust attenuation/turn-off failed at " + base.east(x));
        }
        helper.assertTrue(level.getBlockState(base.east(8)).getValue(RepeaterBlock.POWERED) == on,
            "repeater output failed");
        for (int x = 1; x <= 8; x++) {
            helper.assertTrue(level.getBlockState(base.offset(x, 0, 10)).getValue(PoweredRailBlock.POWERED) == on,
                "powered rail propagation/turn-off failed at " + x);
        }
        helper.assertTrue(level.getBlockState(base.east(9)).getValue(BlockStateProperties.LIT) == on,
            "lamp output failed");
        helper.assertTrue(level.getBlockState(base.offset(2, 0, 8)).getValue(BlockStateProperties.LIT) != on,
            "torch inversion failed at " + base + ", input=" + on + ", state="
                + level.getBlockState(base.offset(2, 0, 8)) + ", support signal="
                + level.getSignal(base.offset(2, -1, 8), Direction.DOWN));
        helper.assertTrue(level.getBlockState(base.offset(6, 0, 8)).getValue(BlockStateProperties.EXTENDED) == on,
            "piston extension/retraction failed");
        helper.assertTrue(level.getBlockState(base.offset(on ? 8 : 7, 0, 8)).is(Blocks.STONE),
            "sticky piston lost its payload");
    }

    private static void comparatorAndObserver(GameTestHelper helper, ServerLevel level, BlockPos base) {
        BlockPos comparator = base.offset(2, 0, 4);
        worker(level, base, () -> tick(level, comparator));
        helper.assertTrue(level.getBlockState(comparator).getSignal(level, comparator, Direction.WEST) == 3,
            "comparator did not read the cauldron's analog signal");
        worker(level, base, () -> {
            level.setBlock(comparator.north(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            level.setBlock(comparator, level.getBlockState(comparator)
                .setValue(ComparatorBlock.MODE, ComparatorMode.SUBTRACT), 3);
            tick(level, comparator);
        });
        helper.assertTrue(level.getBlockState(comparator).getSignal(level, comparator, Direction.WEST) == 0,
            "comparator subtraction ignored side input");
        BlockPos observer = base.offset(10, 0, 4);
        for (boolean powered : new boolean[]{true, false}) {
            worker(level, base, () -> tick(level, observer));
            helper.assertTrue(level.getBlockState(observer).getValue(BlockStateProperties.POWERED) == powered,
                "observer did not produce a complete pulse");
        }
    }

    private static void signalIsolation(GameTestHelper helper, ServerLevel level, BlockPos serial, BlockPos parallel) {
        level.setBlock(serial, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        level.setBlock(parallel, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        RegionTracker.quiesceAndDrain();
        CountDownLatch ready = new CountDownLatch(2);
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger samples = new AtomicInteger();
        RegionWorkers.runAllAndWait(List.of(() -> inRegion(level, parallel, () -> {
            await(ready);
            try {
                for (int i = 0; i < 4000; i++) {
                    level.neighborChanged(parallel.east(), Blocks.REDSTONE_BLOCK, parallel);
                }
            } finally {
                done.set(true);
            }
        }), () -> inRegion(level, serial, () -> {
            await(ready);
            do {
                helper.assertTrue(level.getBlockState(serial.east()).getSignal(level, serial.east(), Direction.UP) == 15,
                    "another worker suppressed this circuit's dust signal");
                samples.incrementAndGet();
            } while (!done.get());
        })));
        helper.assertTrue(samples.get() > 0, "concurrent dust probe did not run");
    }

    private static void worker(ServerLevel level, BlockPos pos, Runnable action) {
        RegionTracker.quiesceAndDrain();
        RegionWorkers.runAllAndWait(List.of(() -> inRegion(level, pos, action), () -> { }));
    }

    private static void inRegion(ServerLevel level, BlockPos pos, Runnable action) {
        LevelRegionIndex index = RegionTracker.index(level);
        var region = index.regionForChunk(new ChunkPos(pos));
        if (region == null) throw new IllegalStateException("redstone fixture has no region owner");
        RegionThreadContext.enter(region, index.levelKey());
        try { action.run(); } finally { RegionThreadContext.exit(); }
    }

    private static void tick(ServerLevel level, BlockPos pos) {
        try {
            Method method = ServerLevel.class.getDeclaredMethod("tickBlock", BlockPos.class, Block.class);
            method.setAccessible(true);
            method.invoke(level, pos, level.getBlockState(pos).getBlock());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("redstone scheduled tick failed", failure);
        }
    }

    private static void await(CountDownLatch ready) {
        ready.countDown();
        try {
            if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("dust workers did not overlap");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static void release(ServerLevel level, BlockPos base) {
        ChunkPos center = new ChunkPos(base);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) level.setChunkForced(center.x + x, center.z + z, false);
        }
    }
}

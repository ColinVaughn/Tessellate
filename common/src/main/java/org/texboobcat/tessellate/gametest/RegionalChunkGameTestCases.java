package org.texboobcat.tessellate.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.monster.Monster;
import org.texboobcat.tessellate.Config;
import org.texboobcat.tessellate.api.TessellateApi;
import org.texboobcat.tessellate.region.RegionTracker;
import org.texboobcat.tessellate.region.PhaseStats;
import org.texboobcat.tessellate.region.ParallelNaturalSpawner;
import org.texboobcat.tessellate.region.LevelRegionIndex;
import org.texboobcat.tessellate.region.MainThreadBoundaries;
import org.texboobcat.tessellate.region.Region;
import org.texboobcat.tessellate.region.RegionWorkers;
import org.texboobcat.tessellate.region.RegionalLevelTicks;
import org.texboobcat.tessellate.guard.RegionThreadContext;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

// Checks the vanilla systems moved into the regional chunk task.
public final class RegionalChunkGameTestCases {

    private RegionalChunkGameTestCases() {
    }

    @SuppressWarnings("PMD.NcssCount")
    public static void mainThreadBoundaryHandoffs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LevelRegionIndex index = RegionTracker.index(level);
        BlockPos start = helper.absolutePos(BlockPos.ZERO).above(2);
        helper.assertTrue(index != null, "test level has no region index");
        index.leaseChunk(net.minecraft.world.level.ChunkPos.asLong(start));
        Region region = index.regionForChunk(new net.minecraft.world.level.ChunkPos(start));
        helper.assertTrue(region != null, "test chunk has no region owner");

        Entity entity = EntityType.ARMOR_STAND.create(level);
        helper.assertTrue(entity != null, "could not create boundary-test entity");
        entity.setPos(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        level.addFreshEntity(entity);

        Entity spawned = EntityType.ARMOR_STAND.create(level);
        helper.assertTrue(spawned != null, "could not create worker-spawned entity");
        spawned.setPos(start.getX() + 1.5, start.getY(), start.getZ() + 0.5);

        double targetX = entity.getX() + 2.0;
        String source = MainThreadBoundaries.source(index.levelKey(), region.id());
        MainThreadBoundaries.Snapshot before = MainThreadBoundaries.snapshot(
            MainThreadBoundaries.Boundary.TELEPORT_DIMENSION);
        Runnable teleport = () -> {
            RegionThreadContext.enter(region, index.levelKey());
            try {
                entity.teleportTo(targetX, entity.getY(), entity.getZ());
            } finally {
                RegionThreadContext.exit();
            }
        };
        Runnable spawn = () -> level.addFreshEntity(spawned);
        RegionWorkers.runAllAndWait(List.of(teleport, spawn));

        helper.assertTrue(entity.getX() != targetX,
            "worker teleport became visible before its main-thread commit");
        helper.assertTrue(level.getEntity(spawned.getUUID()) == null,
            "worker entity insertion became visible before its main-thread commit");
        RegionTracker.quiesceAndDrain();
        MainThreadBoundaries.Snapshot after = MainThreadBoundaries.snapshot(
            MainThreadBoundaries.Boundary.TELEPORT_DIMENSION);
        helper.assertTrue(entity.getX() == targetX,
            "main-thread barrier did not replay the worker teleport");
        helper.assertTrue(level.getEntity(spawned.getUUID()) == spawned,
            "main-thread barrier did not replay the worker entity insertion");
        helper.assertTrue(after.queued() == before.queued() + 1
                && after.replayed() == before.replayed() + 1
                && after.pending() == before.pending() && after.balanced(),
            "teleport boundary did not balance exactly: " + after);
        helper.assertTrue(source.equals(after.lastSource()),
            "teleport boundary lost its source region: " + after.lastSource());

        int remoteChunkX = (start.getX() >> 4) + 512;
        int remoteChunkZ = start.getZ() >> 4;
        BlockPos outputPos = new BlockPos((remoteChunkX << 4) + 14, start.getY(),
            (remoteChunkZ << 4) + 8);
        level.getChunkAt(outputPos).setBlockState(outputPos.east(),
            Blocks.STONE.defaultBlockState(), false);
        helper.assertTrue(!level.hasChunkAt(outputPos.east(2)),
            "output-signal regression setup loaded the adjacent chunk");
        long unavailableBefore = RegionTracker.unavailableChunks();
        RegionWorkers.runAllAndWait(List.of(
            () -> level.updateNeighbourForOutputSignal(outputPos, Blocks.STONE), () -> { }));
        helper.assertTrue(RegionTracker.unavailableChunks() == unavailableBefore,
            "output-signal check requested its unloaded adjacent chunk");
        RegionWorkers.runAllAndWait(List.of(() -> level.getChunk(
            remoteChunkX + 1, remoteChunkZ, ChunkStatus.FULL, false), () -> { }));
        helper.assertTrue(RegionTracker.unavailableChunks() == unavailableBefore
                && RegionTracker.parallelAllowed(),
            "a non-loading chunk probe degraded region ticking");

        assertWorkerChunkFuture(helper, level, start);

        MainThreadBoundaries.Snapshot sectionBefore = MainThreadBoundaries.snapshot(
            MainThreadBoundaries.Boundary.ENTITY_LIFECYCLE);
        double targetY = entity.getY() + 16.0;
        Runnable sectionMove = () -> {
            RegionThreadContext.enter(region, index.levelKey());
            try {
                entity.setPos(entity.getX(), targetY, entity.getZ());
            } finally {
                RegionThreadContext.exit();
            }
        };
        RegionWorkers.runAllAndWait(List.of(sectionMove, () -> { }));
        MainThreadBoundaries.Snapshot sectionQueued = MainThreadBoundaries.snapshot(
            MainThreadBoundaries.Boundary.ENTITY_LIFECYCLE);
        helper.assertTrue(sectionQueued.queued() == sectionBefore.queued() + 2
                && sectionQueued.replayed() == sectionBefore.replayed()
                && sectionQueued.pending() == sectionBefore.pending() + 2,
            "entity section event escaped its worker boundary: " + sectionQueued);
        RegionTracker.quiesceAndDrain();
        MainThreadBoundaries.Snapshot sectionAfter = MainThreadBoundaries.snapshot(
            MainThreadBoundaries.Boundary.ENTITY_LIFECYCLE);
        helper.assertTrue(sectionAfter.queued() == sectionBefore.queued() + 2
                && sectionAfter.replayed() == sectionBefore.replayed() + 2
                && sectionAfter.pending() == sectionBefore.pending()
                && sectionAfter.balanced(),
            "entity section event was not replayed exactly once on main: " + sectionAfter);
        entity.discard();
        spawned.discard();
        helper.succeed();
    }

    private static void assertWorkerChunkFuture(GameTestHelper helper, ServerLevel level,
                                                BlockPos loadedPos) {
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean succeeded = new AtomicBoolean();
        RegionWorkers.runAllAndWait(List.of(() -> {
            var future = level.getChunkSource().getChunkFuture(
                loadedPos.getX() >> 4, loadedPos.getZ() >> 4, ChunkStatus.FULL, true);
            completed.set(future.isDone());
            if (future.isDone()) {
                future.join().ifSuccess(ignored -> succeeded.set(true));
            }
        }, () -> { }));
        helper.assertTrue(completed.get() && succeeded.get(),
            "a worker chunk future escaped to the main-thread distance manager");
    }

    public static void compatibilityApiRunsOnLiveOwners(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LevelRegionIndex index = RegionTracker.index(level);
        BlockPos regionPos = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos mainPos = regionPos.east();
        BlockPos beaconPos = regionPos.south();
        helper.assertTrue(index != null, "test level has no region index");
        index.leaseChunk(net.minecraft.world.level.ChunkPos.asLong(regionPos));
        Region region = index.regionForChunk(new net.minecraft.world.level.ChunkPos(regionPos));
        helper.assertTrue(region != null, "test chunk has no region owner");

        AtomicBoolean regionExecuted = new AtomicBoolean();
        AtomicBoolean regionOwned = new AtomicBoolean();
        AtomicBoolean mainExecuted = new AtomicBoolean();
        AtomicBoolean mainThread = new AtomicBoolean();
        AtomicBoolean entityExecuted = new AtomicBoolean();
        AtomicBoolean entityMainThread = new AtomicBoolean();
        MainThreadBoundaries.Snapshot[] blockEntityBefore = new MainThreadBoundaries.Snapshot[1];

        TessellateApi.executeOnRegion(level, regionPos, () -> {
            regionOwned.set(TessellateApi.isRegionThread()
                && TessellateApi.ownsCurrentRegion(level, regionPos));
            level.setBlockAndUpdate(regionPos, Blocks.EMERALD_BLOCK.defaultBlockState());
            regionExecuted.set(true);
        });

        helper.startSequence()
            .thenExecuteAfter(5, () -> {
                helper.assertTrue(regionExecuted.get() && regionOwned.get(),
                    "executeOnRegion ran without the target ownership scope");
                helper.assertTrue(level.getBlockState(regionPos).is(Blocks.EMERALD_BLOCK),
                    "executeOnRegion did not apply its world mutation");

                Runnable queueMain = () -> {
                    RegionThreadContext.enter(region, index.levelKey());
                    try {
                        TessellateApi.executeOnMainThread(level, mainPos, () -> {
                            mainThread.set(level.getServer().isSameThread());
                            level.setBlockAndUpdate(mainPos, Blocks.GOLD_BLOCK.defaultBlockState());
                            mainExecuted.set(true);
                        });
                    } finally {
                        RegionThreadContext.exit();
                    }
                };
                RegionWorkers.runAllAndWait(List.of(queueMain, () -> { }));
                helper.assertTrue(!mainExecuted.get(),
                    "executeOnMainThread ran synchronously on a worker");
                RegionTracker.quiesceAndDrain();
                helper.assertTrue(mainExecuted.get() && mainThread.get(),
                    "positional main-thread work did not replay on the server thread");
                helper.assertTrue(level.getBlockState(mainPos).is(Blocks.GOLD_BLOCK),
                    "positional main-thread work did not apply its world mutation");

                blockEntityBefore[0] = MainThreadBoundaries.snapshot(
                    MainThreadBoundaries.Boundary.MOD_COMPATIBILITY);
                TessellateApi.registerMainThreadBlockEntity(BlockEntityType.BEACON);
                level.setBlockAndUpdate(beaconPos, Blocks.BEACON.defaultBlockState());
            })
            .thenExecuteAfter(10, () -> {
                MainThreadBoundaries.Snapshot current = MainThreadBoundaries.snapshot(
                    MainThreadBoundaries.Boundary.MOD_COMPATIBILITY);
                helper.assertTrue(current.queued() > blockEntityBefore[0].queued()
                        && current.replayed() > blockEntityBefore[0].replayed(),
                    "registered beacon ticker did not cross the compatibility boundary");
            })
            .thenExecute(() -> {
                level.removeBlock(beaconPos, false);
                TessellateApi.registerMainThreadEntity(EntityType.MARKER);
                Entity probe = new Entity(EntityType.MARKER, level) {
                    @Override
                    protected void defineSynchedData(SynchedEntityData.Builder builder) {
                    }

                    @Override
                    protected void readAdditionalSaveData(CompoundTag tag) {
                    }

                    @Override
                    protected void addAdditionalSaveData(CompoundTag tag) {
                    }

                    @Override
                    public void tick() {
                        entityMainThread.set(level.getServer().isSameThread());
                        entityExecuted.set(true);
                        discard();
                    }
                };
                probe.setPos(regionPos.getX() + 0.5, regionPos.getY() + 1.0,
                    regionPos.getZ() + 0.5);
                helper.assertTrue(level.addFreshEntity(probe),
                    "could not add compatibility API entity probe");
            })
            .thenExecuteAfter(10, () -> helper.assertTrue(
                entityExecuted.get() && entityMainThread.get(),
                "registered entity did not tick on the server thread"))
            .thenSucceed();
    }

    @SuppressWarnings({"removal", "PMD.CognitiveComplexity"})
    public static void regionalChunkTicksAndNaturalSpawning(GameTestHelper helper,
            BiFunction<ServerLevel, String, ServerPlayer> mockPlayerFactory) {
        ServerLevel level = helper.getLevel();
        BlockPos cropPos = helper.absolutePos(BlockPos.ZERO).above();
        BlockPos[] cropPositions = {cropPos, cropPos.offset(2048, 0, 0)};
        ServerPlayer[] players = {
            mockPlayerFactory.apply(level, "test-mock-player-a"),
            mockPlayerFactory.apply(level, "test-mock-player-b")
        };
        for (int i = 0; i < players.length; i++) {
            BlockPos position = cropPositions[i];
            players[i].teleportTo(level, position.getX() + 0.5, position.getY() + 2.0,
                position.getZ() + 0.5, Set.of(), 0.0F, 0.0F);
            level.getChunkSource().move(players[i]);
        }

        var randomTicks = level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING);
        var mobSpawning = level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING);
        int oldRandomTicks = randomTicks.get();
        boolean oldMobSpawning = mobSpawning.get();
        long oldDayTime = level.getDayTime();
        PhaseStats.Snapshot spawningBefore = PhaseStats.snapshot(
            PhaseStats.Phase.NATURAL_SPAWNING);
        ParallelNaturalSpawner.Stats reservationsBefore = ParallelNaturalSpawner.stats();
        int[] topologyStage = {0};
        long[] topologyVersion = {-1L};

        randomTicks.set(3000, level.getServer());
        mobSpawning.set(true, level.getServer());
        level.setDayTime(18000L);
        for (BlockPos position : cropPositions) {
            level.setBlockAndUpdate(position.below(), Blocks.FARMLAND.defaultBlockState()
                .setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE));
            level.setBlockAndUpdate(position, Blocks.WHEAT.defaultBlockState());
            level.setBlockAndUpdate(position.east(), Blocks.GLOWSTONE.defaultBlockState());
        }

        helper.succeedWhen(() -> {
            LevelRegionIndex index = RegionTracker.index(level);
            helper.assertTrue(index != null, "test level has no region index");

            if (topologyStage[0] == 1) {
                Region first = index.regionForChunk(players[0].chunkPosition());
                Region second = index.regionForChunk(players[1].chunkPosition());
                helper.assertTrue(first != null && first == second,
                    "player regions did not merge after moving together");
                players[1].teleportTo(level, cropPositions[1].getX() + 0.5,
                    cropPositions[1].getY() + 2.0, cropPositions[1].getZ() + 0.5,
                    Set.of(), 0.0F, 0.0F);
                level.getChunkSource().move(players[1]);
                topologyStage[0] = 2;
                helper.fail("merged topology passed; waiting for the split and reload");
            }

            Region first = index.regionForChunk(players[0].chunkPosition());
            Region second = index.regionForChunk(players[1].chunkPosition());
            helper.assertTrue(first != null && second != null && first != second,
                "distant players do not own separate regions");
            for (int currentLocation = 0; currentLocation < cropPositions.length;
                 currentLocation++) {
                BlockPos activeCrop = cropPositions[currentLocation];
                index.leaseChunk(net.minecraft.world.level.ChunkPos.asLong(
                    activeCrop.getX() >> 4, activeCrop.getZ() >> 4));
                var monsters = level.getEntitiesOfClass(Monster.class,
                    new AABB(activeCrop).inflate(128.0, 64.0, 128.0));
                helper.assertTrue(!monsters.isEmpty(),
                    "regional natural spawning produced no hostile mob in region "
                        + currentLocation);
                helper.assertTrue(monsters.size() <= 70,
                    "regional hostile mob cap exceeded vanilla's one-player limit in region "
                        + currentLocation + ": " + monsters.size());
                if (topologyStage[0] == 0) {
                    monsters.forEach(Monster::discard);
                }
            }

            if (topologyStage[0] == 0) {
                topologyVersion[0] = index.topologyVersion();
                BlockPos together = cropPositions[0].offset(32, 0, 0);
                players[1].teleportTo(level, together.getX() + 0.5, together.getY() + 2.0,
                    together.getZ() + 0.5, Set.of(), 0.0F, 0.0F);
                level.getChunkSource().move(players[1]);
                topologyStage[0] = 1;
                helper.fail("initial caps passed; waiting for the topology merge");
            }
            helper.assertTrue(index.topologyVersion() > topologyVersion[0],
                "player movement did not exercise region topology changes");

            helper.assertTrue(RegionTracker.violations().total() == 0,
                "regional chunk tick raised an ownership violation");
            helper.assertTrue(RegionTracker.parallelAllowed(),
                "regional chunk tick degraded to serial execution");
            PhaseStats.Snapshot chunkStats = PhaseStats.snapshot(PhaseStats.Phase.CHUNK_TICKS);
            helper.assertTrue(chunkStats.workerCalls() > 0,
                "regional chunk ticks were not observed on workers");
            PhaseStats.Snapshot spawning = PhaseStats.snapshot(
                PhaseStats.Phase.NATURAL_SPAWNING);
            ParallelNaturalSpawner.Stats reservations = ParallelNaturalSpawner.stats();
            if (Config.parallelNaturalSpawning) {
                helper.assertTrue(spawning.workerCalls() > spawningBefore.workerCalls(),
                    "natural spawning was not observed on workers: " + spawning);
                helper.assertTrue(spawning.maxConcurrent() >= 2,
                    "natural spawning remained serialized: " + spawning);
                helper.assertTrue(reservations.committed() + reservations.rolledBack()
                        == reservations.reserved() && reservations.outstanding() == 0,
                    "natural-spawn reservation leaked: " + reservations);
                helper.assertTrue(
                    reservations.capViolations() == reservationsBefore.capViolations(),
                    "natural-spawn cap invariant failed: " + reservations);
                helper.assertTrue(reservations.failures() == reservationsBefore.failures(),
                    "parallel natural spawning reported a failure: " + reservations);
                helper.assertTrue(ParallelNaturalSpawner.parallelAllowed(),
                    "natural spawning fell back to serial: "
                        + ParallelNaturalSpawner.degradeReason());
            } else {
                helper.assertTrue(spawning.mainCalls() > spawningBefore.mainCalls()
                        && spawning.workerCalls() == spawningBefore.workerCalls(),
                    "natural-spawn rollback did not stay on main: " + spawning);
                helper.assertTrue(reservations.reserved() == reservationsBefore.reserved(),
                    "serial rollback unexpectedly used reservations: " + reservations);
            }

            randomTicks.set(oldRandomTicks, level.getServer());
            mobSpawning.set(oldMobSpawning, level.getServer());
            level.setDayTime(oldDayTime);
            for (ServerPlayer player : players) {
                level.getServer().getPlayerList().remove(player);
            }
        });
    }

    public static void pathfindingIsConcurrentOnRegionWorkers(GameTestHelper helper) {
        helper.succeedIf(() -> {
            ServerLevel level = helper.getLevel();
            for (int x = 0; x <= 4; x++) {
                for (int z = 0; z <= 4; z++) {
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 0, z)),
                        Blocks.STONE.defaultBlockState());
                    for (int y = 1; y <= 3; y++) {
                        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)),
                            Blocks.AIR.defaultBlockState());
                    }
                }
            }

            Mob first = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 1, 1));
            Mob second = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(3, 1, 3));
            first.setOnGround(true);
            second.setOnGround(true);

            PhaseStats.Snapshot before = PhaseStats.snapshot(PhaseStats.Phase.PATHFINDING);
            CountDownLatch ready = new CountDownLatch(2);
            AtomicInteger stateChecks = new AtomicInteger();
            AtomicInteger blockedChecks = new AtomicInteger();
            AtomicInteger openChecks = new AtomicInteger();
            AtomicInteger reachablePaths = new AtomicInteger();
            PathTypeCache cache = level.getPathTypeCache();
            BlockPos blocked = helper.absolutePos(new BlockPos(2, 0, 2));
            BlockPos open = blocked.above();
            try {
                RegionWorkers.runAllAndWait(List.of(
                    () -> findPathsTogether(first, helper.absolutePos(new BlockPos(3, 1, 3)),
                        ready, cache, level, blocked, open, stateChecks, blockedChecks, openChecks,
                        reachablePaths),
                    () -> findPathsTogether(second, helper.absolutePos(new BlockPos(1, 1, 1)),
                        ready, cache, level, blocked, open, stateChecks, blockedChecks, openChecks,
                        reachablePaths)));

                PhaseStats.Snapshot after = PhaseStats.snapshot(PhaseStats.Phase.PATHFINDING);
                helper.assertTrue(stateChecks.get() == 64 && blockedChecks.get() == 64
                        && openChecks.get() == 64,
                    "concurrent path reads were incorrect: states=" + stateChecks.get()
                        + "/64, blocked=" + blockedChecks.get() + "/64, open="
                        + openChecks.get() + "/64");
                helper.assertTrue(after.workerCalls() - before.workerCalls() >= 64,
                    "path searches did not run on region workers: " + after);
                helper.assertTrue(reachablePaths.get() == 64,
                    "region-worker path searches could not reach their targets: "
                        + reachablePaths.get() + "/64");
                helper.assertTrue(after.maxConcurrent() >= 2,
                    "path searches remained serialized: " + after);
                helper.assertTrue(after.failures() == before.failures(),
                    "a worker path search failed: " + after);
                helper.assertTrue(RegionTracker.violations().total() == 0,
                    "concurrent path searches raised an ownership violation");
                helper.assertTrue(RegionTracker.parallelAllowed(),
                    "concurrent path searches degraded to serial execution");
            } finally {
                first.discard();
                second.discard();
            }
        });
    }

    public static void mobAiTicksOnRegionWorkers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 0, z)),
                    Blocks.STONE.defaultBlockState());
            }
        }

        Mob sheep = helper.spawn(EntityType.SHEEP, new BlockPos(2, 1, 2));
        Mob attacker = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2, 1, 3));
        double sheepX = sheep.getX();
        double sheepZ = sheep.getZ();
        helper.assertTrue(sheep.hurt(level.damageSources().mobAttack(attacker), 1.0F),
            "could not trigger sheep panic");

        helper.runAfterDelay(60, () -> {
            double dx = sheep.getX() - sheepX;
            double dz = sheep.getZ() - sheepZ;
            helper.assertTrue(dx * dx + dz * dz > 0.25,
                "damaged sheep did not run panic AI");
            sheep.discard();
            attacker.discard();
            helper.succeed();
        });
    }

    public static void scheduledTickRouterPreservesVanillaContainerSemantics(
        GameTestHelper helper) {
        helper.succeedIf(() -> {
            ServerLevel level = helper.getLevel();
            BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
            BlockPos first = new BlockPos((origin.getX() >> 4 << 4) + 4, origin.getY(),
                (origin.getZ() >> 4 << 4) + 4);
            LevelRegionIndex index = RegionTracker.index(level);
            if (index != null) {
                index.leaseChunk(net.minecraft.world.level.ChunkPos.asLong(
                    first.getX() >> 4, first.getZ() >> 4));
            }
            helper.assertTrue(level.getBlockTicks() instanceof RegionalLevelTicks,
                "server level did not install the regional scheduled-tick router");

            var container = level.getChunkAt(first).getBlockTicks();
            int before = container.count();
            BlockPos second = first.east();
            BlockPos third = second.east();
            long trigger = level.getGameTime() + 1000L;
            level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.STONE, first, trigger, 1L));
            level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.STONE, second, trigger, 2L));
            level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.STONE, third, trigger, 3L));
            level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.STONE, third, trigger, 4L));
            helper.assertTrue(container.count() == before + 3,
                "regional scheduler did not preserve per-position deduplication: before="
                    + before + ", after=" + container.count());

            BoundingBox source = new BoundingBox(first.getX(), first.getY(), first.getZ(),
                third.getX(), third.getY(), third.getZ());
            Vec3i offset = new Vec3i(0, 0, 4);
            level.getBlockTicks().copyArea(source, offset);
            helper.assertTrue(container.count() == before + 6,
                "regional scheduler did not copy every queued tick exactly once");
            helper.assertTrue(level.getBlockTicks().hasScheduledTick(first.offset(offset),
                    Blocks.STONE),
                "copied regional tick is not queryable");

            level.getBlockTicks().clearArea(source);
            helper.assertTrue(container.count() == before + 3,
                "regional scheduler clearArea removed the wrong ticks");
            level.getBlockTicks().clearArea(new BoundingBox(
                first.getX(), first.getY(), first.getZ() + 4,
                third.getX(), third.getY(), third.getZ() + 4));
            helper.assertTrue(container.count() == before,
                "regional scheduler left copied ticks behind after cleanup");

            Region region = index == null ? null
                : index.regionForChunk(new net.minecraft.world.level.ChunkPos(first));
            helper.assertTrue(region != null, "test chunk has no region owner");
            AtomicInteger executed = new AtomicInteger();
            level.getBlockTicks().schedule(
                new ScheduledTick<>(Blocks.STONE, first, level.getGameTime(), 5L));
            level.getBlockTicks().schedule(
                new ScheduledTick<>(Blocks.STONE, second, level.getGameTime(), 6L));
            RegionThreadContext.enter(region, index.levelKey());
            try {
                level.getBlockTicks().tick(level.getGameTime(), 2, (pos, block) -> {
                    executed.incrementAndGet();
                    if (pos.equals(first)) {
                        helper.assertTrue(level.getBlockTicks().willTickThisTick(second, block),
                            "willTickThisTick lost the remaining vanilla run queue");
                    }
                });
            } finally {
                RegionThreadContext.exit();
            }
            helper.assertTrue(executed.get() == 2 && container.count() == before,
                "regional scheduler did not execute both accepted ticks exactly once");
        });
    }

    public static void blockEventRouterPreservesOrderDedupAndMainThreadPackets(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos chestPos = pos.east();
        BlockPos bellPos = pos.south();
        BlockPos unloaded = pos.offset(4096, 0, 4096);
        BoundingBox area = new BoundingBox(pos.getX(), pos.getY(), pos.getZ(),
            pos.getX(), pos.getY(), pos.getZ());
        BoundingBox unloadedArea = new BoundingBox(
            unloaded.getX(), unloaded.getY(), unloaded.getZ(),
            unloaded.getX(), unloaded.getY(), unloaded.getZ());
        PhaseStats.Snapshot[] before = new PhaseStats.Snapshot[2];
        long[] callbacksAfterFirstPass = new long[1];

        helper.startSequence()
            .thenExecute(() -> {
                level.setBlockAndUpdate(pos, Blocks.SHULKER_BOX.defaultBlockState());
                level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
                level.setBlockAndUpdate(bellPos, Blocks.BELL.defaultBlockState());
                before[0] = PhaseStats.snapshot(PhaseStats.Phase.BLOCK_EVENTS);
                before[1] = PhaseStats.snapshot(PhaseStats.Phase.BLOCK_EVENT_PACKETS);
                level.blockEvent(pos, Blocks.SHULKER_BOX, 1, 1);
                level.blockEvent(pos, Blocks.SHULKER_BOX, 1, 1);
                level.blockEvent(pos, Blocks.SHULKER_BOX, 1, 0);
                level.blockEvent(chestPos, Blocks.CHEST, 1, 1);
                level.blockEvent(bellPos, Blocks.BELL, 1, Direction.NORTH.get3DDataValue());
            })
            .thenExecuteAfter(10, () -> {
                ShulkerBoxBlockEntity box = (ShulkerBoxBlockEntity) level.getBlockEntity(pos);
                helper.assertTrue(box != null
                        && box.getAnimationStatus()
                            == ShulkerBoxBlockEntity.AnimationStatus.CLOSED,
                    "regional block events did not preserve open-then-close order");
                helper.assertTrue(level.getBlockEntity(chestPos) instanceof ChestBlockEntity,
                    "chest block event lost its block entity");
                BellBlockEntity bell = (BellBlockEntity) level.getBlockEntity(bellPos);
                helper.assertTrue(bell != null && bell.shaking
                        && bell.clickDirection == Direction.NORTH,
                    "bell block event did not reach its block entity");

                PhaseStats.Snapshot events = PhaseStats.snapshot(PhaseStats.Phase.BLOCK_EVENTS);
                PhaseStats.Snapshot packets = PhaseStats.snapshot(
                    PhaseStats.Phase.BLOCK_EVENT_PACKETS);
                long callbackDelta = events.workerCalls() + events.mainCalls()
                    - before[0].workerCalls() - before[0].mainCalls();
                helper.assertTrue(callbackDelta == 4,
                    "duplicate block event was not collapsed: " + callbackDelta);
                helper.assertTrue(packets.workerCalls() == before[1].workerCalls()
                        && packets.mainCalls() - before[1].mainCalls() == 4,
                    "successful block-event packets did not stay on main: " + packets);
                LevelRegionIndex index = RegionTracker.index(level);
                helper.assertTrue(index != null && index.blockEvents().pendingCount() == 0,
                    "regional block-event queue did not drain");

                callbacksAfterFirstPass[0] = events.workerCalls() + events.mainCalls();
                level.blockEvent(pos, Blocks.SHULKER_BOX, 1, 1);
                level.clearBlockEvents(area);
            })
            .thenExecuteAfter(10, () -> {
                ShulkerBoxBlockEntity box = (ShulkerBoxBlockEntity) level.getBlockEntity(pos);
                helper.assertTrue(box != null
                        && box.getAnimationStatus()
                            == ShulkerBoxBlockEntity.AnimationStatus.CLOSED,
                    "clearBlockEvents left a cleared callback active");
                PhaseStats.Snapshot events = PhaseStats.snapshot(PhaseStats.Phase.BLOCK_EVENTS);
                helper.assertTrue(events.workerCalls() + events.mainCalls()
                        == callbacksAfterFirstPass[0],
                    "clearBlockEvents did not remove the regional event");
                LevelRegionIndex index = RegionTracker.index(level);
                helper.assertTrue(index != null && index.blockEvents().pendingCount() == 0,
                    "clearBlockEvents stranded a regional event");

                helper.assertTrue(!level.hasChunkAt(unloaded),
                    "unloaded block-event test position unexpectedly loaded");
                level.blockEvent(unloaded, Blocks.SHULKER_BOX, 1, 1);
            })
            .thenExecuteAfter(10, () -> {
                LevelRegionIndex index = RegionTracker.index(level);
                helper.assertTrue(index != null && index.blockEvents().pendingCount() == 1,
                    "unloaded block event was not rescheduled");
                level.clearBlockEvents(unloadedArea);
            })
            .thenExecuteAfter(2, () -> {
                LevelRegionIndex index = RegionTracker.index(level);
                helper.assertTrue(index != null && index.blockEvents().pendingCount() == 0,
                    "clearing an unloaded block event left it queued");
            })
            .thenSucceed();
    }

    @SuppressWarnings("PMD.CyclomaticComplexity")
    private static void findPathsTogether(Mob mob, BlockPos target, CountDownLatch ready,
                                           PathTypeCache cache, ServerLevel level, BlockPos blocked,
                                           BlockPos open, AtomicInteger stateChecks,
                                           AtomicInteger blockedChecks, AtomicInteger openChecks,
                                           AtomicInteger reachablePaths) {
        ready.countDown();
        try {
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out starting concurrent path searches");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted starting concurrent path searches",
                interrupted);
        }

        for (int i = 0; i < 32; i++) {
            if (level.getBlockState(blocked).is(Blocks.STONE)
                    && level.getBlockState(open).isAir()) {
                stateChecks.incrementAndGet();
            }
            if (cache.getOrCompute(level, blocked) == PathType.BLOCKED) {
                blockedChecks.incrementAndGet();
            }
            if (cache.getOrCompute(level, open) == PathType.OPEN) {
                openChecks.incrementAndGet();
            }
            mob.getNavigation().stop();
            var path = mob.getNavigation().createPath(Set.of(target), 0);
            if (path != null && path.canReach()) {
                reachablePaths.incrementAndGet();
            }
        }
    }

}

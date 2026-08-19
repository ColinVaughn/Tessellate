package org.texboobcat.optimal.region;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.texboobcat.optimal.Config;
import org.texboobcat.optimal.guard.RegionThreadContext;
import org.texboobcat.optimal.mixin.LevelTicksAccessor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Routes each chunk's ordinary vanilla tick container to its current region scheduler. */
public final class RegionalLevelTicks<T> extends LevelTicks<T> {

    /** Lets the existing LevelTicks mixin distinguish safe owner-local child calls. */
    public interface WorkerOwned {
    }

    private static final int UNOWNED = -1;

    private record OwnedContainer<T>(LevelChunkTicks<T> container, LevelTicks<T> ticks,
                                     int regionId) {
    }

    private static final class ChildLevelTicks<T> extends LevelTicks<T> implements WorkerOwned {
        private ChildLevelTicks(LongPredicate tickCheck, Supplier<ProfilerFiller> profiler) {
            super(tickCheck, profiler);
        }
    }

    private final LongPredicate tickCheck;
    private final Supplier<ProfilerFiller> profiler;
    private final LevelTicks<T> unowned;
    private final Map<Integer, LevelTicks<T>> regions = new ConcurrentHashMap<>();
    private final Map<Long, OwnedContainer<T>> containers = new ConcurrentHashMap<>();
    private volatile LevelRegionIndex index;
    private volatile boolean regionalRouting = Config.scopedScheduledTicks;
    private boolean containersChanged = true;
    private long topologyVersion = Long.MIN_VALUE;

    public RegionalLevelTicks(LongPredicate tickCheck, Supplier<ProfilerFiller> profiler) {
        // The superclass remains empty. Every public operation below routes to a vanilla child.
        super(tickCheck, profiler);
        this.tickCheck = tickCheck;
        this.profiler = profiler;
        this.unowned = new ChildLevelTicks<>(tickCheck, profiler);
    }

    @Override
    public void addContainer(ChunkPos chunkPos, LevelChunkTicks<T> chunkTicks) {
        long key = chunkPos.toLong();
        OwnedContainer<T> previous = this.containers.remove(key);
        if (previous != null) {
            locked(previous.ticks(), () -> previous.ticks().removeContainer(chunkPos));
        }
        locked(this.unowned, () -> this.unowned.addContainer(chunkPos, chunkTicks));
        this.containers.put(key, new OwnedContainer<>(chunkTicks, this.unowned, UNOWNED));
        this.containersChanged = true;
    }

    @Override
    public void removeContainer(ChunkPos chunkPos) {
        OwnedContainer<T> owned = this.containers.remove(chunkPos.toLong());
        if (owned != null) {
            locked(owned.ticks(), () -> owned.ticks().removeContainer(chunkPos));
            this.containersChanged = true;
        }
    }

    @Override
    public void schedule(ScheduledTick<T> tick) {
        OwnedContainer<T> owned = this.containers.get(ChunkPos.asLong(tick.pos()));
        if (owned == null) {
            Util.pauseInIde(new IllegalStateException(
                "Trying to schedule tick in not loaded position " + tick.pos()));
            return;
        }

        Region bound = RegionThreadContext.current();
        if (RegionWorkers.isWorkerThread()
            && (bound == null || owned.regionId() != bound.id())) {
            // Wait for the source task first. Replay will defer once more if the destination is
            // still owned when the main thread reaches it.
            DeferredMainThreadWork.defer(
                MainThreadBoundaries.Boundary.CROSS_REGION_WRITES,
                () -> this.schedule(tick));
            return;
        }

        LevelRegionIndex currentIndex = this.index;
        if (!RegionWorkers.isWorkerThread() && owned.regionId() >= 0 && currentIndex != null
            && !currentIndex.regionIdle(owned.regionId())) {
            DeferredMainThreadWork.deferForRegion(
                MainThreadBoundaries.Boundary.CROSS_REGION_WRITES,
                currentIndex.levelKey(), owned.regionId(), () -> this.schedule(tick));
            return;
        }
        locked(owned.ticks(), () -> owned.ticks().schedule(tick));
    }

    @Override
    public void tick(long gameTime, int maxAllowedTicks, BiConsumer<BlockPos, T> ticker) {
        LevelTicks<T> child = activeChild();
        if (child != null) {
            locked(child, () -> child.tick(gameTime, maxAllowedTicks, ticker));
        }
    }

    @Override
    public boolean hasScheduledTick(BlockPos pos, T type) {
        OwnedContainer<T> owned = this.containers.get(ChunkPos.asLong(pos));
        return owned != null && queryIsLocal(owned) && locked(owned.ticks(),
            () -> owned.ticks().hasScheduledTick(pos, type));
    }

    @Override
    public boolean willTickThisTick(BlockPos pos, T type) {
        OwnedContainer<T> owned = this.containers.get(ChunkPos.asLong(pos));
        return owned != null && queryIsLocal(owned) && locked(owned.ticks(),
            () -> owned.ticks().willTickThisTick(pos, type));
    }

    @Override
    public void clearArea(BoundingBox area) {
        if (RegionWorkers.isWorkerThread()) {
            DeferredMainThreadWork.defer(
                MainThreadBoundaries.Boundary.CROSS_REGION_WRITES,
                () -> this.clearArea(area));
            return;
        }
        for (LevelTicks<T> child : schedulers()) {
            locked(child, () -> child.clearArea(area));
        }
    }

    @Override
    public void copyArea(BoundingBox area, Vec3i offset) {
        copyAreaFrom(this, area, offset);
    }

    @Override
    public void copyAreaFrom(LevelTicks<T> source, BoundingBox area, Vec3i offset) {
        if (RegionWorkers.isWorkerThread()) {
            DeferredMainThreadWork.defer(
                MainThreadBoundaries.Boundary.CROSS_REGION_WRITES,
                () -> this.copyAreaFrom(source, area, offset));
            return;
        }
        if (!(source instanceof RegionalLevelTicks<?> regionalSource)) {
            super.copyAreaFrom(source, area, offset);
            return;
        }

        @SuppressWarnings("unchecked")
        RegionalLevelTicks<T> typedSource = (RegionalLevelTicks<T>) regionalSource;
        List<ScheduledTick<T>> copied = typedSource.ticksIn(area);
        LongSummaryStatistics orders = copied.stream()
            .mapToLong(ScheduledTick::subTickOrder).summaryStatistics();
        long min = orders.getMin();
        long max = orders.getMax();
        for (ScheduledTick<T> tick : copied) {
            schedule(new ScheduledTick<>(tick.type(), tick.pos().offset(offset),
                tick.triggerTick(), tick.priority(), tick.subTickOrder() - min + max + 1L));
        }
    }

    @Override
    public int count() {
        if (RegionWorkers.isWorkerThread()) {
            LevelTicks<T> child = activeChild();
            RegionTracker.degradeToSerial(
                "a region worker queried the level-wide scheduled-tick count");
            return child == null ? 0 : locked(child, child::count);
        }
        int total = 0;
        for (LevelTicks<T> child : schedulers()) {
            total += locked(child, child::count);
        }
        return total;
    }

    /** Rehomes containers after the index has applied this tick's queued topology changes. */
    public void reconcile(LevelRegionIndex index) {
        this.index = index;
        boolean regional = Config.scopedScheduledTicks;
        long currentTopologyVersion = index.topologyVersion();
        if (!this.containersChanged && this.topologyVersion == currentTopologyVersion
            && this.regionalRouting == regional) {
            return;
        }
        if (this.regionalRouting != regional) {
            // A runtime mode change can touch every owner, rather than a topology-blocked subset.
            index.awaitAllRegions();
        }

        Set<Integer> active = rehomeContainers(index, regional);
        this.regions.keySet().removeIf(id -> !active.contains(id));
        this.regionalRouting = regional;
        this.topologyVersion = currentTopologyVersion;
        this.containersChanged = false;
    }

    private Set<Integer> rehomeContainers(LevelRegionIndex index, boolean regional) {
        Set<Integer> active = new HashSet<>();
        for (Map.Entry<Long, OwnedContainer<T>> entry : this.containers.entrySet()) {
            long key = entry.getKey();
            OwnedContainer<T> owned = entry.getValue();
            Region region = regional ? index.regionForChunk(new ChunkPos(key)) : null;
            int targetId = region == null ? UNOWNED : region.id();
            if (targetId >= 0) {
                active.add(targetId);
            }
            if (targetId == owned.regionId()) {
                continue;
            }

            ChunkPos pos = new ChunkPos(key);
            LevelTicks<T> target = targetId < 0 ? this.unowned : child(targetId);
            locked(owned.ticks(), () -> owned.ticks().removeContainer(pos));
            locked(target, () -> target.addContainer(pos, owned.container()));
            this.containers.replace(key, owned,
                new OwnedContainer<>(owned.container(), target, targetId));
        }
        return active;
    }

    private boolean queryIsLocal(OwnedContainer<T> owned) {
        if (!RegionWorkers.isWorkerThread()) {
            return true;
        }
        Region bound = RegionThreadContext.current();
        if (bound != null && owned.regionId() == bound.id()) {
            return true;
        }
        RegionTracker.degradeToSerial("a region worker queried scheduled ticks owned by "
            + (owned.regionId() < 0 ? "the unowned scheduler" : "region#" + owned.regionId()));
        return false;
    }

    private LevelTicks<T> activeChild() {
        Region region = RegionThreadContext.current();
        return this.regionalRouting && region != null
            ? this.regions.get(region.id())
            : this.unowned;
    }

    private LevelTicks<T> child(int regionId) {
        return this.regions.computeIfAbsent(regionId,
            ignored -> new ChildLevelTicks<>(this.tickCheck, this.profiler));
    }

    private List<LevelTicks<T>> schedulers() {
        List<LevelTicks<T>> result = new ArrayList<>(this.regions.size() + 1);
        result.add(this.unowned);
        result.addAll(this.regions.values());
        return result;
    }

    private List<ScheduledTick<T>> ticksIn(BoundingBox area) {
        Predicate<ScheduledTick<T>> inside = tick -> area.isInside(tick.pos());
        List<ScheduledTick<T>> result = new ArrayList<>();
        for (LevelTicks<T> child : schedulers()) {
            locked(child, () -> {
                @SuppressWarnings("unchecked")
                LevelTicksAccessor<T> accessor = (LevelTicksAccessor<T>) (Object) child;
                accessor.optimal$alreadyRunThisTick().stream().filter(inside).forEach(result::add);
                accessor.optimal$toRunThisTick().stream().filter(inside).forEach(result::add);
                Long2ObjectMap<LevelChunkTicks<T>> containers = accessor.optimal$allContainers();
                int minX = SectionPos.blockToSectionCoord(area.minX());
                int minZ = SectionPos.blockToSectionCoord(area.minZ());
                int maxX = SectionPos.blockToSectionCoord(area.maxX());
                int maxZ = SectionPos.blockToSectionCoord(area.maxZ());
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        LevelChunkTicks<T> container = containers.get(ChunkPos.asLong(x, z));
                        if (container != null) {
                            container.getAll().filter(inside).forEach(result::add);
                        }
                    }
                }
            });
        }
        return result;
    }

    private static void locked(Object lock, Runnable action) {
        synchronized (lock) {
            action.run();
        }
    }

    private static <R> R locked(Object lock, Supplier<R> action) {
        synchronized (lock) {
            return action.get();
        }
    }
}

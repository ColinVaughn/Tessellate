package org.texboobcat.tessellate.region;

import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.texboobcat.tessellate.Config;
import org.texboobcat.tessellate.guard.RegionThreadContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Region-owned equivalent of ServerLevel's ordered, deduplicating block-event set. */
public final class RegionalBlockEvents {

    private static final int UNOWNED = -1;

    private static final class EventQueue {
        private final LinkedHashSet<BlockEventData> events = new LinkedHashSet<>();

        synchronized boolean add(BlockEventData event) {
            return this.events.add(event);
        }

        synchronized BlockEventData poll() {
            Iterator<BlockEventData> iterator = this.events.iterator();
            if (!iterator.hasNext()) {
                return null;
            }
            BlockEventData event = iterator.next();
            iterator.remove();
            return event;
        }

        synchronized boolean remove(BlockEventData event) {
            return this.events.remove(event);
        }

        synchronized int removeInside(BoundingBox area) {
            int before = this.events.size();
            this.events.removeIf(event -> area.isInside(event.pos()));
            return before - this.events.size();
        }

        synchronized List<BlockEventData> snapshot() {
            return List.copyOf(this.events);
        }

        synchronized boolean isEmpty() {
            return this.events.isEmpty();
        }
    }

    private final ServerLevel level;
    private final LevelRegionIndex index;
    private final EventQueue unowned = new EventQueue();
    private final Map<Integer, EventQueue> regions = new ConcurrentHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger peakPending = new AtomicInteger();
    private volatile boolean regionalRouting = Config.scopedBlockEvents;
    private long topologyVersion = Long.MIN_VALUE;

    public RegionalBlockEvents(ServerLevel level, LevelRegionIndex index) {
        this.level = level;
        this.index = index;
    }

    public void add(net.minecraft.core.BlockPos pos, Block block, int eventId, int eventParam) {
        add(new BlockEventData(pos, block, eventId, eventParam));
    }

    private void add(BlockEventData event) {
        int targetId = ownerId(event);
        Region bound = RegionThreadContext.current();
        if (RegionWorkers.isWorkerThread()
            && (bound == null || targetId != bound.id())) {
            DeferredMainThreadWork.defer(
                MainThreadBoundaries.Boundary.CROSS_REGION_WRITES, () -> add(event));
            return;
        }
        if (!RegionWorkers.isWorkerThread() && targetId >= 0
            && !this.index.regionIdle(targetId)) {
            DeferredMainThreadWork.deferForRegion(
                MainThreadBoundaries.Boundary.CROSS_REGION_WRITES,
                this.index.levelKey(), targetId, () -> add(event));
            return;
        }
        add(queue(targetId), event);
    }

    /** Runs the queue selected by the current region binding, or the unowned queue on main. */
    public void drainCurrent() {
        Region bound = RegionThreadContext.current();
        int queueId = this.regionalRouting && bound != null ? bound.id() : UNOWNED;
        EventQueue queue = queueId < 0 ? this.unowned : this.regions.get(queueId);
        if (queue == null) {
            return;
        }

        List<BlockEventData> reschedule = new ArrayList<>(64);
        BlockEventData event;
        while ((event = poll(queue)) != null) {
            process(event, queueId, reschedule);
        }

        // Vanilla drains events raised by callbacks in this same pass. Only positions that were
        // not ticking are appended after the pass and retried next tick.
        for (BlockEventData deferred : reschedule) {
            add(queue, deferred);
        }
    }

    private void process(BlockEventData event, int queueId, List<BlockEventData> reschedule) {
        if (ownerId(event) != queueId) {
            add(event);
            return;
        }
        if (!this.level.shouldTickBlocksAt(event.pos())) {
            reschedule.add(event);
            return;
        }

        BlockState state = this.level.getBlockState(event.pos());
        if (!state.is(event.block())) {
            return;
        }
        boolean[] handled = {false};
        PhaseStats.measure(PhaseStats.Phase.BLOCK_EVENTS,
            () -> handled[0] = state.triggerEvent(this.level, event.pos(),
                event.paramA(), event.paramB()));
        if (handled[0]) {
            broadcast(event);
        }
    }

    /** Matches clearBlockEvents: clear queued events, not an event already being processed. */
    public void clearArea(BoundingBox area) {
        if (RegionWorkers.isWorkerThread()) {
            DeferredMainThreadWork.defer(
                MainThreadBoundaries.Boundary.CROSS_REGION_WRITES, () -> clearArea(area));
            return;
        }

        Set<Long> affectedChunks = new HashSet<>();
        for (EventQueue queue : queues()) {
            for (BlockEventData event : queue.snapshot()) {
                if (area.isInside(event.pos())) {
                    affectedChunks.add(ChunkPos.asLong(event.pos()));
                }
            }
        }
        for (long chunk : affectedChunks) {
            this.index.leaseChunk(chunk);
        }
        for (EventQueue queue : queues()) {
            int removed = queue.removeInside(area);
            if (removed != 0) {
                this.pending.addAndGet(-removed);
            }
        }
    }

    /** Rehomes queued events after the existing quiescent topology boundary. */
    public void reconcile() {
        boolean regional = Config.scopedBlockEvents;
        long currentTopology = this.index.topologyVersion();
        if (regional == this.regionalRouting && currentTopology == this.topologyVersion) {
            return;
        }
        if (regional != this.regionalRouting) {
            this.index.awaitAllRegions();
            this.regionalRouting = regional;
        }

        for (Map.Entry<Integer, EventQueue> entry : this.regions.entrySet()) {
            moveMisowned(entry.getKey(), entry.getValue());
        }
        moveMisowned(UNOWNED, this.unowned);
        this.regions.entrySet().removeIf(entry -> entry.getValue().isEmpty()
            && this.index.regionizer().regionForId(entry.getKey()) == null);
        this.topologyVersion = currentTopology;
    }

    public int pendingCount() {
        return this.pending.get();
    }

    public int peakPendingCount() {
        return this.peakPending.get();
    }

    public boolean regionalRouting() {
        return this.regionalRouting;
    }

    private void moveMisowned(int sourceId, EventQueue source) {
        for (BlockEventData event : source.snapshot()) {
            int targetId = ownerId(event);
            if (targetId != sourceId && remove(source, event)) {
                add(queue(targetId), event);
            }
        }
    }

    private int ownerId(BlockEventData event) {
        if (!this.regionalRouting) {
            return UNOWNED;
        }
        Region region = this.index.regionForChunk(new ChunkPos(event.pos()));
        return region == null ? UNOWNED : region.id();
    }

    private EventQueue queue(int regionId) {
        return regionId < 0 ? this.unowned
            : this.regions.computeIfAbsent(regionId, ignored -> new EventQueue());
    }

    private List<EventQueue> queues() {
        List<EventQueue> result = new ArrayList<>(this.regions.size() + 1);
        result.add(this.unowned);
        result.addAll(this.regions.values());
        return result;
    }

    private void add(EventQueue queue, BlockEventData event) {
        if (queue.add(event)) {
            int count = this.pending.incrementAndGet();
            this.peakPending.accumulateAndGet(count, Math::max);
        }
    }

    private BlockEventData poll(EventQueue queue) {
        BlockEventData event = queue.poll();
        if (event != null) {
            this.pending.decrementAndGet();
        }
        return event;
    }

    private boolean remove(EventQueue queue, BlockEventData event) {
        if (!queue.remove(event)) {
            return false;
        }
        this.pending.decrementAndGet();
        return true;
    }

    private void broadcast(BlockEventData event) {
        Runnable send = () -> MainThreadBoundaries.measure(
            MainThreadBoundaries.Boundary.CHUNK_PLAYER_BROADCASTS,
            () -> PhaseStats.measure(PhaseStats.Phase.BLOCK_EVENT_PACKETS,
                () -> this.level.getServer().getPlayerList().broadcast(
                    null,
                    event.pos().getX(), event.pos().getY(), event.pos().getZ(),
                    64.0,
                    this.level.dimension(),
                    new ClientboundBlockEventPacket(event.pos(), event.block(),
                        event.paramA(), event.paramB()))));
        if (RegionWorkers.isWorkerThread()) {
            DeferredMainThreadWork.defer(
                MainThreadBoundaries.Boundary.CHUNK_PLAYER_BROADCASTS, send);
        } else {
            send.run();
        }
    }
}

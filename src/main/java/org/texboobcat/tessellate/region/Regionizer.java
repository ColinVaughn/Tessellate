package org.texboobcat.tessellate.region;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

// Groups entity-ticking sections into regions: connected components under Chebyshev distance
// <= mergeRadius.
//
// The separation invariant the design depends on is a consequence of that definition rather than
// an extra rule. If two ticking sections are within mergeRadius of each other they are in
// the same component, so two distinct regions are always more than mergeRadius
// sections apart. With shift 2 and radius 2 that is a guaranteed 128-block buffer, wider than mob
// target scans, pathfinder range and neighbor-update propagation.
//
// Changes are queued and applied only in update(), which runs on the main thread after
// the affected merge neighborhood is idle. Unrelated regions may keep ticking.
//
// Contains no Minecraft types.
public final class Regionizer {

    private final int sectionShift;
    private final int mergeRadius;
    private final RegionizerListener listener;

    private final Long2ObjectMap<Region> sectionToRegion = new Long2ObjectOpenHashMap<>();
    private final Int2ObjectMap<Region> regionsById = new Int2ObjectOpenHashMap<>();

    private final LongSet pendingAdd = new LongOpenHashSet();
    private final LongSet pendingRemove = new LongOpenHashSet();

    // Ticking chunks per section. A section stays in a region while at least one of its chunks is
    // ticking, so chunk churn inside a section costs nothing.
    private final Long2ObjectMap<LongSet> chunksBySection = new Long2ObjectOpenHashMap<>();

    private int nextRegionId = 1;
    private long mergeCount;
    private long splitCount;

    public Regionizer(int sectionShift, int mergeRadius, RegionizerListener listener) {
        if (sectionShift < 0 || sectionShift > 8) {
            throw new IllegalArgumentException("sectionShift out of range: " + sectionShift);
        }
        if (mergeRadius < 1) {
            throw new IllegalArgumentException("mergeRadius must be >= 1: " + mergeRadius);
        }
        this.sectionShift = sectionShift;
        this.mergeRadius = mergeRadius;
        this.listener = listener;
    }

    public int sectionShift() {
        return this.sectionShift;
    }

    public int mergeRadius() {
        return this.mergeRadius;
    }

    public int regionCount() {
        return this.regionsById.size();
    }

    public Collection<Region> regions() {
        return Collections.unmodifiableCollection(this.regionsById.values());
    }

    public long mergeCount() {
        return this.mergeCount;
    }

    public long splitCount() {
        return this.splitCount;
    }

    public Region regionForId(int id) {
        return this.regionsById.get(id);
    }

    public Region regionForSection(long section) {
        return this.sectionToRegion.get(section);
    }

    public Region regionForChunk(int chunkX, int chunkZ) {
        return this.sectionToRegion.get(RegionSectionPos.fromChunk(chunkX, chunkZ, this.sectionShift));
    }

    public Region regionForChunkLong(long chunkPos) {
        return this.sectionToRegion.get(RegionSectionPos.fromChunkLong(chunkPos, this.sectionShift));
    }

    // ---- queueing -------------------------------------------------------------------------

    // Chunk key uses the ChunkPos#asLong convention: x low 32 bits, z high 32 bits.
    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkZ << 32) | (chunkX & 0xFFFFFFFFL);
    }

    public void queueAddChunk(int chunkX, int chunkZ) {
        queueAddChunkLong(chunkKey(chunkX, chunkZ));
    }

    public void queueAddChunkLong(long chunkPos) {
        long section = RegionSectionPos.fromChunkLong(chunkPos, this.sectionShift);
        LongSet chunks = this.chunksBySection.get(section);
        if (chunks == null) {
            chunks = new LongOpenHashSet();
            this.chunksBySection.put(section, chunks);
        }
        if (chunks.add(chunkPos) && chunks.size() == 1) {
            this.pendingAdd.add(section);
            this.pendingRemove.remove(section);
        }
    }

    public void queueRemoveChunk(int chunkX, int chunkZ) {
        queueRemoveChunkLong(chunkKey(chunkX, chunkZ));
    }

    public void queueRemoveChunkLong(long chunkPos) {
        long section = RegionSectionPos.fromChunkLong(chunkPos, this.sectionShift);
        LongSet chunks = this.chunksBySection.get(section);
        if (chunks == null) {
            return;
        }
        if (chunks.remove(chunkPos) && chunks.isEmpty()) {
            this.chunksBySection.remove(section);
            this.pendingRemove.add(section);
            this.pendingAdd.remove(section);
        }
    }

    public boolean hasPendingChanges() {
        return !this.pendingAdd.isEmpty() || !this.pendingRemove.isEmpty();
    }

    // ---- apply ----------------------------------------------------------------------------

    // Applies queued changes on the main thread after their affected regions are idle.
    public void update() {
        if (!this.pendingRemove.isEmpty()) {
            LongSet removals = new LongOpenHashSet(this.pendingRemove);
            this.pendingRemove.clear();
            for (LongIterator it = removals.iterator(); it.hasNext(); ) {
                applyRemove(it.nextLong());
            }
        }
        if (!this.pendingAdd.isEmpty()) {
            LongSet additions = new LongOpenHashSet(this.pendingAdd);
            this.pendingAdd.clear();
            for (LongIterator it = additions.iterator(); it.hasNext(); ) {
                applyAdd(it.nextLong());
            }
        }
    }

    private void applyAdd(long section) {
        if (this.sectionToRegion.containsKey(section)) {
            return;
        }

        List<Region> neighbors = neighboringRegions(section);

        if (neighbors.isEmpty()) {
            Region region = new Region(this.nextRegionId++, this.sectionShift);
            region.addSection(section);
            this.regionsById.put(region.id(), region);
            this.sectionToRegion.put(section, region);
            this.listener.onRegionCreated(region);
            return;
        }

        Region survivor = largest(neighbors);
        survivor.addSection(section);
        this.sectionToRegion.put(section, survivor);

        if (neighbors.size() > 1) {
            List<Region> absorbed = new ArrayList<>(neighbors.size() - 1);
            for (Region other : neighbors) {
                if (other == survivor) {
                    continue;
                }
                for (LongIterator it = other.sections().iterator(); it.hasNext(); ) {
                    long s = it.nextLong();
                    survivor.addSection(s);
                    this.sectionToRegion.put(s, survivor);
                }
                this.regionsById.remove(other.id());
                absorbed.add(other);
            }
            this.mergeCount++;
            this.listener.onRegionsMerged(survivor, absorbed);
            for (Region dead : absorbed) {
                this.listener.onRegionDestroyed(dead);
            }
        }
    }

    private void applyRemove(long section) {
        Region region = this.sectionToRegion.remove(section);
        if (region == null) {
            return;
        }
        region.removeSection(section);

        if (region.sectionCount() == 0) {
            this.regionsById.remove(region.id());
            this.listener.onRegionDestroyed(region);
            return;
        }

        List<LongSet> components = connectedComponents(region.sections());
        if (components.size() <= 1) {
            return;
        }
        splitRegion(region, components);
    }

    private void splitRegion(Region region, List<LongSet> components) {
        // The largest component keeps the original region so that region identity is as stable as
        // possible; the rest become new regions.
        int largestIndex = largestComponent(components);

        List<Region> splitOff = new ArrayList<>(components.size() - 1);
        for (int i = 0; i < components.size(); i++) {
            if (i == largestIndex) {
                continue;
            }
            splitOff.add(splitComponent(region, components.get(i)));
        }

        this.splitCount++;
        this.listener.onRegionSplit(region, splitOff);
        for (Region fresh : splitOff) {
            this.listener.onRegionCreated(fresh);
        }
    }

    private static int largestComponent(List<LongSet> components) {
        int largest = 0;
        for (int i = 1; i < components.size(); i++) {
            if (components.get(i).size() > components.get(largest).size()) {
                largest = i;
            }
        }
        return largest;
    }

    private Region splitComponent(Region original, LongSet component) {
        Region fresh = new Region(this.nextRegionId++, this.sectionShift);
        fresh.addAllSections(component);
        this.regionsById.put(fresh.id(), fresh);
        for (LongIterator it = component.iterator(); it.hasNext(); ) {
            long section = it.nextLong();
            original.removeSection(section);
            this.sectionToRegion.put(section, fresh);
        }
        return fresh;
    }

    // Distinct regions owning a section within mergeRadius of section.
    private List<Region> neighboringRegions(long section) {
        int sx = RegionSectionPos.x(section);
        int sz = RegionSectionPos.z(section);
        List<Region> found = new ArrayList<>(4);
        for (int dx = -this.mergeRadius; dx <= this.mergeRadius; dx++) {
            for (int dz = -this.mergeRadius; dz <= this.mergeRadius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                Region region = this.sectionToRegion.get(RegionSectionPos.pack(sx + dx, sz + dz));
                if (region != null && !found.contains(region)) {
                    found.add(region);
                }
            }
        }
        return found;
    }

    private static Region largest(List<Region> regions) {
        Region best = regions.get(0);
        for (int i = 1; i < regions.size(); i++) {
            if (regions.get(i).sectionCount() > best.sectionCount()) {
                best = regions.get(i);
            }
        }
        return best;
    }

    // Connected components of sections under Chebyshev distance <= mergeRadius.
    private List<LongSet> connectedComponents(LongSet sections) {
        List<LongSet> components = new ArrayList<>();
        LongSet unvisited = new LongOpenHashSet(sections);

        while (!unvisited.isEmpty()) {
            long start = unvisited.iterator().nextLong();
            unvisited.remove(start);
            components.add(connectedComponent(start, unvisited));
        }
        return components;
    }

    private LongSet connectedComponent(long start, LongSet unvisited) {
        LongSet component = new LongOpenHashSet();
        component.add(start);
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        queue.enqueue(start);

        while (!queue.isEmpty()) {
            addConnectedNeighbors(queue.dequeueLong(), unvisited, component, queue);
        }
        return component;
    }

    private void addConnectedNeighbors(long current, LongSet unvisited, LongSet component,
                                       LongArrayFIFOQueue queue) {
        int cx = RegionSectionPos.x(current);
        int cz = RegionSectionPos.z(current);
        for (int dx = -this.mergeRadius; dx <= this.mergeRadius; dx++) {
            for (int dz = -this.mergeRadius; dz <= this.mergeRadius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                long neighbor = RegionSectionPos.pack(cx + dx, cz + dz);
                if (unvisited.remove(neighbor)) {
                    component.add(neighbor);
                    queue.enqueue(neighbor);
                }
            }
        }
    }
}

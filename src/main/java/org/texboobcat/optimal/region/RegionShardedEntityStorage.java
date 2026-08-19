package org.texboobcat.optimal.region;

import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.AbstractObjectCollection;
import it.unimi.dsi.fastutil.objects.AbstractObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectIterators;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.texboobcat.optimal.Config;
import org.texboobcat.optimal.guard.Ownership;
import org.texboobcat.optimal.mixin.EntitySectionStorageAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.LongStream;
import java.util.stream.Stream;

// Entity section storage split into per-cell shards, so that two region threads never touch the
// same container.
//
// Why shard by cell rather than by region
//
// Giving each region its own storage looks natural and is wrong: regions merge and split, so
// every merge would have to migrate live map entries between storages while entities are moving
// through them.
//
// Section keys make a better split available for free. An entity section is a 16-block cube, so
// a section key's x and z are chunk coordinates, and therefore map to a fixed grid cell
// with no reference to any region. A region is a set of cells and a cell belongs to at most one
// region, so sharding by cell gives a region exclusive ownership of its shards while merge and
// split migrate nothing at all.
//
// Why a shard is a real EntitySectionStorage
//
// Each shard is a plain vanilla instance rather than a reimplementation, for two reasons.
// Per-shard query semantics are then vanilla's own, so results are identical instead of
// hopefully-equivalent. And Lithium's entity/fast_retrieval mixin, which cancels
// forEachAccessibleNonEmptySection and replaces the AVL walk with a direct coordinate walk,
// still applies inside every shard. Reimplementing the traversal here would have silently bypassed
// that optimization and shown up as a performance regression.
//
// This class overrides every method that would otherwise touch the superclass's own sections map
// and sectionIds tree; those inherited fields stay empty. The one exception
// is sections, which is replaced with ShardedSectionView because third-party code
// reads that field directly. See the class comment there.
public final class RegionShardedEntityStorage<T extends EntityAccess> extends EntitySectionStorage<T> {

    private final Class<T> entityClass;
    private final Long2ObjectFunction<Visibility> initialVisibility;
    private final int sectionShift;

    // Copy-on-write, so routing a key never takes a lock and never boxes.
    //
    // Writes happen only when a 2^shift-square of chunks first gains an entity section, which is
    // rare enough that copying the map costs nothing measurable. This mirrors how vanilla treats
    // ChunkMap.visibleChunkMap, which the worker chunk-read path already relies on.
    private volatile Long2ObjectMap<EntitySectionStorage<T>> shards = new Long2ObjectOpenHashMap<>();

    private final Object shardLock = new Object();

    // The level this storage belongs to, bound at level load.
    //
    // Not available at construction: the storage is built inside the entity manager's
    // constructor, which runs before the level finishes building itself. Volatile because the
    // binding happens on the main thread while workers may already be reading.
    private volatile LevelRegionIndex index;

    public void bindIndex(LevelRegionIndex index) {
        this.index = index;
    }

    public void leaseSection(long sectionKey) {
        LevelRegionIndex bound = this.index;
        if (bound != null && Config.asyncRegionLoops
            && org.texboobcat.optimal.guard.RegionThreadContext.onMainThread()) {
            bound.leaseCell(this.cellOfSection(sectionKey));
        }
    }

    public void leaseChunk(long chunkPos) {
        LevelRegionIndex bound = this.index;
        if (bound != null && Config.asyncRegionLoops
            && org.texboobcat.optimal.guard.RegionThreadContext.onMainThread()) {
            bound.leaseCell(this.cellOfChunk(chunkPos));
        }
    }

    public RegionShardedEntityStorage(Class<T> entityClass,
                                      Long2ObjectFunction<Visibility> initialVisibility,
                                      int sectionShift) {
        super(entityClass, initialVisibility);
        this.entityClass = entityClass;
        this.initialVisibility = initialVisibility;
        this.sectionShift = sectionShift;
        // The inherited map must stay readable for code that reads the field rather than calling a
        // method. Installed last, so the view never sees a half-built object.
        accessorOf(this).optimal$setSections(new ShardedSectionView());
        synchronized (LIVE) {
            LIVE.add(this);
        }
    }

    // Live instances, for the /optimal regions readout.
    //
    // Weak, so a level that unloads is not pinned in memory by a diagnostic. The storage has no
    // back-reference to its level, and adding accessors just to name it in a status line would
    // couple more of the mod to vanilla internals than the readout is worth.
    private static final java.util.Set<RegionShardedEntityStorage<?>> LIVE =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public static int liveInstances() {
        synchronized (LIVE) {
            return LIVE.size();
        }
    }

    public static int totalShards() {
        synchronized (LIVE) {
            int total = 0;
            for (RegionShardedEntityStorage<?> storage : LIVE) {
                total += storage.shardCount();
            }
            return total;
        }
    }

    public int shardCount() {
        return this.shards.size();
    }

    // Snapshot of accessible entities in the shards owned by one stable region.
    public List<T> entitiesInRegion(Region region) {
        List<T> entities = new ArrayList<>();
        Long2ObjectMap<EntitySectionStorage<T>> current = this.shards;
        for (LongIterator cells = region.sections().iterator(); cells.hasNext(); ) {
            EntitySectionStorage<T> shard = current.get(cells.nextLong());
            if (shard == null) {
                continue;
            }
            for (EntitySection<T> section : sectionsOf(shard).values()) {
                if (section.getStatus().isAccessible()) {
                    section.getEntities().forEach(entities::add);
                }
            }
        }
        return entities;
    }

    // ---- routing ---------------------------------------------------------------------------

    // EntitySectionStorage only implements the accessor once Mixin has applied it, and
    // this class is final, so the cast has to go through the non-final superclass type.
    private static EntitySectionStorageAccessor accessorOf(EntitySectionStorage<?> storage) {
        return (EntitySectionStorageAccessor) storage;
    }

    // Section x/z are chunk coordinates, so the cell is a pure function of the key.
    private long cellOfSection(long sectionKey) {
        return RegionSectionPos.pack(
            RegionShardBounds.cellCoord(SectionPos.x(sectionKey), this.sectionShift),
            RegionShardBounds.cellCoord(SectionPos.z(sectionKey), this.sectionShift));
    }

    private long cellOfChunk(long chunkPos) {
        return RegionSectionPos.fromChunk(ChunkPos.getX(chunkPos), ChunkPos.getZ(chunkPos),
            this.sectionShift);
    }

    @Nullable
    private EntitySectionStorage<T> shardIfPresent(long cell) {
        return this.shards.get(cell);
    }

    private EntitySectionStorage<T> shardFor(long cell) {
        EntitySectionStorage<T> existing = this.shards.get(cell);
        if (existing != null) {
            return existing;
        }
        synchronized (this.shardLock) {
            EntitySectionStorage<T> recheck = this.shards.get(cell);
            if (recheck != null) {
                return recheck;
            }
            EntitySectionStorage<T> created =
                new EntitySectionStorage<>(this.entityClass, this.initialVisibility);
            Long2ObjectOpenHashMap<EntitySectionStorage<T>> copy =
                new Long2ObjectOpenHashMap<>(this.shards);
            copy.put(cell, created);
            this.shards = copy;
            return created;
        }
    }

    // ---- single-key operations -------------------------------------------------------------

    @Override
    public EntitySection<T> getOrCreateSection(long sectionPos) {
        long cell = this.cellOfSection(sectionPos);
        assertWritable(cell);
        return this.shardFor(cell).getOrCreateSection(sectionPos);
    }

    // Off by default and checked with a static boolean first, so the disabled path is a branch the
    // JIT hoists rather than a call. Only writes are checked. Reads can cross into the shared buffer
    // between regions, and checking them would tax the hottest path in the game.
    private void assertWritable(long cell) {
        LevelRegionIndex bound = this.index;
        if (bound != null && Config.asyncRegionLoops
            && org.texboobcat.optimal.guard.RegionThreadContext.onMainThread()) {
            bound.leaseCell(cell);
        }
        if (!Config.assertShardOwnership) {
            return;
        }
        // A worker writing outside its own region, and the main thread writing into a region that
        // is still ticking, are the two halves of the same rule.
        Ownership.checkRegionCell(cell, RegionTracker.violations(), Config.strictGuard);
        if (bound != null) {
            bound.assertCellNotInFlight(cell);
        }
    }

    @Nullable
    @Override
    public EntitySection<T> getSection(long sectionPos) {
        EntitySectionStorage<T> shard = this.shardIfPresent(this.cellOfSection(sectionPos));
        return shard == null ? null : shard.getSection(sectionPos);
    }

    @Override
    public void remove(long sectionId) {
        long cell = this.cellOfSection(sectionId);
        assertWritable(cell);
        EntitySectionStorage<T> shard = this.shardIfPresent(cell);
        if (shard == null) {
            return;
        }
        shard.remove(sectionId);
        if (shard.count() == 0) {
            this.dropEmptyShard(cell, shard);
        }
    }

    // Drops a shard once its last section is gone.
    //
    // Not just housekeeping. Sections are created and then removed as a player explores, so
    // without this the map accumulates one empty shard per area ever visited, and every full scan
    // pays for them, including the mob-cap count that walks all sections once per tick.
    //
    // Safe under the same ownership rule the rest of the design rests on: only the thread that
    // owns a cell touches that cell's shard, so nothing can be inserting into this shard while it
    // is being dropped. Readers are unaffected either way, because they hold a snapshot of the map
    // and would simply see an empty shard.
    private void dropEmptyShard(long cell, EntitySectionStorage<T> shard) {
        synchronized (this.shardLock) {
            EntitySectionStorage<T> current = this.shards.get(cell);
            // Re-check under the lock: the shard may have been replaced, or refilled, since.
            if (current != shard || current.count() != 0) {
                return;
            }
            Long2ObjectOpenHashMap<EntitySectionStorage<T>> copy =
                new Long2ObjectOpenHashMap<>(this.shards);
            copy.remove(cell);
            this.shards = copy;
        }
    }

    // A chunk lies entirely inside one cell, so both chunk queries route to a single shard.

    @Override
    public LongStream getExistingSectionPositionsInChunk(long pos) {
        EntitySectionStorage<T> shard = this.shardIfPresent(this.cellOfChunk(pos));
        return shard == null ? LongStream.empty() : shard.getExistingSectionPositionsInChunk(pos);
    }

    @Override
    public Stream<EntitySection<T>> getExistingSectionsInChunk(long pos) {
        EntitySectionStorage<T> shard = this.shardIfPresent(this.cellOfChunk(pos));
        return shard == null ? Stream.empty() : shard.getExistingSectionsInChunk(pos);
    }

    // ---- fan-out -----------------------------------------------------------------------------

    // Visits the shards the box touches, handing each the original box.
    //
    // Shard selection must use the same margins vanilla applies internally (2 blocks in x and z)
    // or a section just outside the box but inside vanilla's expanded range would be dropped
    // because its shard was never visited. Each shard then re-applies that expansion itself, so the
    // set of sections visited is exactly vanilla's.
    @Override
    public void forEachAccessibleNonEmptySection(AABB boundingBox,
                                                 AbortableIterationConsumer<EntitySection<T>> consumer) {
        Long2ObjectMap<EntitySectionStorage<T>> current = this.shards;
        if (current.isEmpty()) {
            return;
        }

        int minCellX = RegionShardBounds.minCell(boundingBox.minX, this.sectionShift);
        int maxCellX = RegionShardBounds.maxCell(boundingBox.maxX, this.sectionShift);
        int minCellZ = RegionShardBounds.minCell(boundingBox.minZ, this.sectionShift);
        int maxCellZ = RegionShardBounds.maxCell(boundingBox.maxZ, this.sectionShift);

        // The overwhelmingly common case is a single entity's collision box, which lands inside one
        // cell. Handle it without allocating the abort tracker, since this is the hottest read path
        // in the game.
        if (minCellX == maxCellX && minCellZ == maxCellZ) {
            EntitySectionStorage<T> shard = current.get(RegionSectionPos.pack(minCellX, minCellZ));
            if (shard != null) {
                shard.forEachAccessibleNonEmptySection(boundingBox, consumer);
            }
            return;
        }

        AbortTracker<T> tracker = new AbortTracker<>(consumer);
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                EntitySectionStorage<T> shard = current.get(RegionSectionPos.pack(cellX, cellZ));
                if (shard == null) {
                    continue;
                }
                shard.forEachAccessibleNonEmptySection(boundingBox, tracker);
                if (tracker.aborted) {
                    return;
                }
            }
        }
    }

    // Propagates an abort across the shard fan-out.
    //
    // Vanilla returns early from its own loop when the consumer aborts; a shard can only stop its
    // own traversal, so the fan-out has to notice and stop visiting further shards.
    private static final class AbortTracker<T extends EntityAccess>
        implements AbortableIterationConsumer<EntitySection<T>> {

        private final AbortableIterationConsumer<EntitySection<T>> delegate;
        boolean aborted;

        AbortTracker(AbortableIterationConsumer<EntitySection<T>> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Continuation accept(EntitySection<T> section) {
            Continuation continuation = this.delegate.accept(section);
            if (continuation.shouldAbort()) {
                this.aborted = true;
            }
            return continuation;
        }
    }

    @Override
    public LongSet getAllChunksWithExistingSections() {
        LongSet all = new LongOpenHashSet();
        for (EntitySectionStorage<T> shard : this.shards.values()) {
            all.addAll(shard.getAllChunksWithExistingSections());
        }
        return all;
    }

    @Override
    public int count() {
        int total = 0;
        for (EntitySectionStorage<T> shard : this.shards.values()) {
            total += shard.count();
        }
        return total;
    }

    // ---- the inherited sections field --------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Long2ObjectMap<EntitySection<T>> sectionsOf(EntitySectionStorage<T> shard) {
        return (Long2ObjectMap<EntitySection<T>>) (Long2ObjectMap<?>)
            accessorOf(shard).optimal$getSections();
    }

    // A read-only view of every shard's sections, installed into the inherited sections
    // field.
    //
    // This exists because third-party code reads that field rather than calling a method.
    // Lithium's spawning optimization shadows sections and iterates its values to count
    // mobs against the spawn cap; against an empty map it would count zero and spawning would never
    // stop. Leaving the field empty would not crash. It would quietly break mob caps,
    // which is exactly the silent-failure mode this work is supposed to avoid.
    //
    // Reads chain lazily over the shards. Writes throw rather than silently going nowhere, so a
    // mod that mutates the field directly fails loudly and visibly.
    private final class ShardedSectionView extends it.unimi.dsi.fastutil.longs.AbstractLong2ObjectMap<EntitySection<T>> {

        @Override
        public EntitySection<T> get(long key) {
            return RegionShardedEntityStorage.this.getSection(key);
        }

        @Override
        public boolean containsKey(long key) {
            return RegionShardedEntityStorage.this.getSection(key) != null;
        }

        @Override
        public int size() {
            return RegionShardedEntityStorage.this.count();
        }

        @Override
        public ObjectCollection<EntitySection<T>> values() {
            return new AbstractObjectCollection<EntitySection<T>>() {
                @Override
                public ObjectIterator<EntitySection<T>> iterator() {
                    return chain(shard -> sectionsOf(shard).values().iterator());
                }

                @Override
                public int size() {
                    return RegionShardedEntityStorage.this.count();
                }
            };
        }

        @Override
        public ObjectSet<Entry<EntitySection<T>>> long2ObjectEntrySet() {
            return new AbstractObjectSet<Entry<EntitySection<T>>>() {
                @Override
                public ObjectIterator<Entry<EntitySection<T>>> iterator() {
                    return chain(shard -> sectionsOf(shard).long2ObjectEntrySet().iterator());
                }

                @Override
                public int size() {
                    return RegionShardedEntityStorage.this.count();
                }
            };
        }

        @Override
        public EntitySection<T> put(long key, EntitySection<T> value) {
            throw new UnsupportedOperationException(
                "optimal: entity sections are sharded; write through EntitySectionStorage instead "
                    + "of the sections field");
        }

        @Override
        public EntitySection<T> remove(long key) {
            throw new UnsupportedOperationException(
                "optimal: entity sections are sharded; write through EntitySectionStorage instead "
                    + "of the sections field");
        }
    }

    // Lazily concatenates one iterator per shard, so a full scan allocates almost nothing.
    private <E> ObjectIterator<E> chain(java.util.function.Function<EntitySectionStorage<T>, ObjectIterator<E>> open) {
        ObjectIterator<EntitySectionStorage<T>> shardIterator = this.shards.values().iterator();
        return new ObjectIterator<>() {
            private ObjectIterator<E> current = ObjectIterators.emptyIterator();

            @Override
            public boolean hasNext() {
                while (!this.current.hasNext()) {
                    if (!shardIterator.hasNext()) {
                        return false;
                    }
                    this.current = open.apply(shardIterator.next());
                }
                return true;
            }

            @Override
            public E next() {
                if (!this.hasNext()) {
                    throw new NoSuchElementException();
                }
                return this.current.next();
            }
        };
    }
}

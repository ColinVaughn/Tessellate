package org.texboobcat.optimal.region;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import org.texboobcat.optimal.mixin.PersistentEntitySectionManagerAccessor;
import org.texboobcat.optimal.mixin.ServerLevelEntityManagerAccessor;

// Walks level to entity manager to section storage, through the accessor mixins.
//
// Lives outside the mixin package on purpose: that package is owned by the mixin config, and a
// plain class inside it cannot be referenced directly. Accessor interfaces are the
// exception and are meant to be called from outside, which is what this does.
public final class EntityStorageBinding {

    private EntityStorageBinding() {
    }

    // Returns the level's sharded storage, or null when the split is not enabled.
    public static RegionShardedEntityStorage<?> shardedStorageOf(ServerLevel level) {
        PersistentEntitySectionManager<?> manager =
            ((ServerLevelEntityManagerAccessor) level).optimal$getEntityManager();
        EntitySectionStorage<?> storage =
            ((PersistentEntitySectionManagerAccessor) manager).optimal$getSectionStorage();
        return storage instanceof RegionShardedEntityStorage<?> sharded ? sharded : null;
    }

    // The level's own entity callbacks, used to replay callbacks a worker deferred.
    @SuppressWarnings("unchecked")
    public static LevelCallback<Entity> callbacksOf(ServerLevel level) {
        PersistentEntitySectionManager<?> manager =
            ((ServerLevelEntityManagerAccessor) level).optimal$getEntityManager();
        return (LevelCallback<Entity>)
            ((PersistentEntitySectionManagerAccessor) manager).optimal$getCallbacks();
    }

    // Copies the chunks vanilla has confirmed as entity-ticking.
    public static void copyTickingChunks(ServerLevel level, LongSet target) {
        PersistentEntitySectionManager<?> manager =
            ((ServerLevelEntityManagerAccessor) level).optimal$getEntityManager();
        Long2ObjectMap<Visibility> visibility =
            ((PersistentEntitySectionManagerAccessor) manager).optimal$getChunkVisibility();
        for (Long2ObjectMap.Entry<Visibility> entry : visibility.long2ObjectEntrySet()) {
            if (entry.getValue().isTicking()) {
                target.add(entry.getLongKey());
            }
        }
    }
}

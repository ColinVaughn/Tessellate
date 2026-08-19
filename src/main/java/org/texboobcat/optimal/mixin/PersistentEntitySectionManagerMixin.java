package org.texboobcat.optimal.mixin;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.texboobcat.optimal.Config;
import org.texboobcat.optimal.region.RegionShardedEntityStorage;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;

// Swaps the level's entity section storage for the per-cell sharded one.
//
// Done at construction rather than lazily, because the storage is handed to a
// LevelEntityGetterAdapter that is final and captured across the level. Replacing
// it later would leave stale references reading the old container.
@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin<T extends EntityAccess> {

    private static final Logger OPTIMAL_LOGGER = LogUtils.getLogger();

    @Shadow
    @Final
    @Mutable
    EntitySectionStorage<T> sectionStorage;

    @Shadow
    @Final
    @Mutable
    private LevelEntityGetter<T> entityGetter;

    @Shadow
    @Final
    private EntityLookup<T> visibleEntityStorage;

    @Shadow
    @Final
    @Mutable
    private Long2ObjectMap<Visibility> chunkVisibility;

    @Shadow
    @Final
    @Mutable
    Set<UUID> knownUuids;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void optimal$installShardedStorage(Class<T> entityClass,
                                               LevelCallback<T> callbacks,
                                               EntityPersistentStorage<T> permanentStorage,
                                               CallbackInfo ci) {
        if (!Config.shardEntityStorage) {
            return;
        }

        // The same visibility map instance is reused, so the default return value vanilla
        // configures on it in this very constructor still applies to sections created by shards.
        // Point reads and writes happen on different region/main threads. The synchronized
        // fastutil view is enough: this map is never iterated on the hot entity-query path.
        this.chunkVisibility = it.unimi.dsi.fastutil.longs.Long2ObjectMaps.synchronize(
            this.chunkVisibility);
        RegionShardedEntityStorage<T> sharded =
            new RegionShardedEntityStorage<>(entityClass, this.chunkVisibility, Config.sectionShift);
        this.sectionStorage = sharded;
        this.entityGetter = new LevelEntityGetterAdapter<>(this.visibleEntityStorage, sharded);

        // Written on every entity add, which entity ticking itself causes constantly, so two
        // region workers would otherwise write this set with nothing between them.
        this.knownUuids = ConcurrentHashMap.newKeySet();

        OPTIMAL_LOGGER.info("optimal: entity sections sharded by region cell (shift {})",
            Config.sectionShift);
    }

    @Inject(method = "addEntity", at = @At("HEAD"))
    private void optimal$leaseEntityAdd(T entity, boolean worldGenSpawned,
                                        CallbackInfoReturnable<Boolean> cir) {
        RegionShardedEntityStorage<T> sharded = optimal$sharded();
        if (sharded != null) {
            sharded.leaseSection(net.minecraft.core.SectionPos.asLong(entity.blockPosition()));
        }
    }

    @Inject(
        method = "updateChunkStatus(Lnet/minecraft/world/level/ChunkPos;"
            + "Lnet/minecraft/world/level/entity/Visibility;)V",
        at = @At("HEAD"))
    private void optimal$leaseChunkStatus(net.minecraft.world.level.ChunkPos pos,
                                          Visibility visibility, CallbackInfo ci) {
        RegionShardedEntityStorage<T> sharded = optimal$sharded();
        if (sharded != null) {
            sharded.leaseChunk(pos.toLong());
        }
    }

    @Inject(method = "storeChunkSections", at = @At("HEAD"))
    private void optimal$leaseEntitySave(long chunkPos, Consumer<T> action,
                                         CallbackInfoReturnable<Boolean> cir) {
        RegionShardedEntityStorage<T> sharded = optimal$sharded();
        if (sharded != null) {
            sharded.leaseChunk(chunkPos);
        }
    }

    @SuppressWarnings("unchecked")
    @org.jetbrains.annotations.Nullable
    private RegionShardedEntityStorage<T> optimal$sharded() {
        return this.sectionStorage instanceof RegionShardedEntityStorage<?> sharded
            ? (RegionShardedEntityStorage<T>) sharded
            : null;
    }
}

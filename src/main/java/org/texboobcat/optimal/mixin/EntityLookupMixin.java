package org.texboobcat.optimal.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityLookup;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.texboobcat.optimal.Config;
import org.texboobcat.optimal.region.ConcurrentInt2ObjectMap;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Makes the level's entity id and UUID indexes safe to write from more than one thread.
//
// Sharding by cell fixes the spatial containers, but these two are not spatially keyed and stay
// level-wide. They are written whenever an entity is added or removed, which entity ticking itself
// causes through events such as mob deaths, item spawns, and fired arrows. Two region workers
// would write the same map with nothing between them.
//
// chunkVisibility is deliberately not converted. It is written only by chunk status
// changes on the main thread, so it is made safe by keeping chunk processing out of the parallel
// window rather than paying for a concurrent container on every section creation.
@Mixin(EntityLookup.class)
public abstract class EntityLookupMixin<T extends EntityAccess> {

    private static final org.slf4j.Logger OPTIMAL_LOGGER =
        com.mojang.logging.LogUtils.getLogger();

    @Shadow
    @Final
    @Mutable
    private Int2ObjectMap<T> byId;

    @Shadow
    @Final
    @Mutable
    private Map<UUID, T> byUuid;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void optimal$makeIndexesConcurrent(CallbackInfo ci) {
        if (!Config.shardEntityStorage) {
            return;
        }
        this.byId = new ConcurrentInt2ObjectMap<>();
        this.byUuid = new ConcurrentHashMap<>();
        // Logged because a swap that silently did not happen looks exactly like one that did,
        // right up until two workers corrupt the index.
        OPTIMAL_LOGGER.info("optimal: entity id/uuid indexes made concurrent");
    }
}

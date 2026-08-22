package org.texboobcat.tessellate.api;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.texboobcat.tessellate.guard.RegionThreadContext;
import org.texboobcat.tessellate.region.DeferredMainThreadWork;
import org.texboobcat.tessellate.region.LevelRegionIndex;
import org.texboobcat.tessellate.region.MainThreadBoundaries;
import org.texboobcat.tessellate.region.RegionTracker;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Compatibility hooks for mods whose entity ticks are not worker-thread safe. */
public final class TessellateApi {

    private static final Set<EntityType<?>> MAIN_THREAD_ENTITY_TYPES =
        ConcurrentHashMap.newKeySet();
    private static final Set<BlockEntityType<?>> MAIN_THREAD_BLOCK_ENTITY_TYPES =
        ConcurrentHashMap.newKeySet();
    private static volatile ConfiguredEntities configuredEntities = configuredEntities(Set.of());

    private record ConfiguredEntities(Set<ResourceLocation> ids,
                                      ConcurrentMap<EntityType<?>, Boolean> decisions) {
    }

    private TessellateApi() {
    }

    /**
     * Makes every entity of {@code type} tick on the server's main thread.
     * Call this once during mod initialization. Repeated registrations are harmless.
     */
    public static void registerMainThreadEntity(EntityType<?> type) {
        MAIN_THREAD_ENTITY_TYPES.add(Objects.requireNonNull(type, "type"));
    }

    /**
     * Makes every block entity of {@code type} tick on the server's main thread.
     * This is a compatibility fallback; prefer dispatching only the unsafe operation.
     */
    public static void registerMainThreadBlockEntity(BlockEntityType<?> type) {
        MAIN_THREAD_BLOCK_ENTITY_TYPES.add(Objects.requireNonNull(type, "type"));
    }

    public static boolean requiresMainThreadEntityTick(EntityType<?> type) {
        Objects.requireNonNull(type, "type");
        if (MAIN_THREAD_ENTITY_TYPES.contains(type)) {
            return true;
        }
        ConfiguredEntities configured = configuredEntities;
        return configured.decisions().computeIfAbsent(type, candidate ->
            configured.ids().contains(BuiltInRegistries.ENTITY_TYPE.getKey(candidate)));
    }

    static void configureMainThreadEntities(Collection<String> entityIds) {
        Objects.requireNonNull(entityIds, "entityIds");
        Set<ResourceLocation> ids = entityIds.stream()
            .filter(Objects::nonNull)
            .map(ResourceLocation::tryParse)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        configuredEntities = configuredEntities(ids);
    }

    public static boolean requiresMainThreadBlockEntityTick(BlockEntityType<?> type) {
        return MAIN_THREAD_BLOCK_ENTITY_TYPES.contains(Objects.requireNonNull(type, "type"));
    }

    public static boolean hasMainThreadBlockEntityTypes() {
        return !MAIN_THREAD_BLOCK_ENTITY_TYPES.isEmpty();
    }

    /** Returns whether the current call is executing inside a Tessellate region scope. */
    public static boolean isRegionThread() {
        return RegionThreadContext.currentBinding() != null;
    }

    /** Returns whether the current region scope owns {@code pos} in {@code level}. */
    public static boolean ownsCurrentRegion(ServerLevel level, BlockPos pos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        RegionThreadContext.Binding binding = RegionThreadContext.currentBinding();
        return binding != null
            && binding.levelKey().equals(level.dimension().location().toString())
            && binding.region().ownsChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** Fails when code attempts a positional operation outside its owning region scope. */
    public static void requireCurrentRegion(ServerLevel level, BlockPos pos) {
        if (!ownsCurrentRegion(level, pos)) {
            throw new IllegalStateException("current region does not own " + pos + " in "
                + level.dimension().location());
        }
    }

    /**
     * Runs {@code work} in the region currently owning {@code pos}. Owner-local calls run inline;
     * all other calls are queued and return immediately.
     */
    public static void executeOnRegion(ServerLevel level, BlockPos pos, Runnable work) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(work, "work");
        if (ownsCurrentRegion(level, pos)) {
            work.run();
            return;
        }
        LevelRegionIndex index = RegionTracker.index(level);
        if (index == null) {
            executeOnMainThread(work);
        } else {
            index.enqueueRegionWork(pos.immutable(), work);
        }
    }

    private static ConfiguredEntities configuredEntities(Set<ResourceLocation> ids) {
        return new ConfiguredEntities(ids, new ConcurrentHashMap<>());
    }

    /**
     * Queues {@code work} for the server's main thread. This method always returns before the work
     * runs, so callers must not depend on its result.
     */
    public static void executeOnMainThread(Runnable work) {
        DeferredMainThreadWork.defer(MainThreadBoundaries.Boundary.MOD_COMPATIBILITY,
            Objects.requireNonNull(work, "work"));
    }

    /**
     * Queues main-thread work and leases the region owning {@code target} before it runs.
     */
    public static void executeOnMainThread(ServerLevel level, BlockPos target, Runnable work) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(work, "work");
        executeOnMainThread(() -> {
            LevelRegionIndex index = RegionTracker.index(level);
            if (index != null) {
                index.leaseChunk(net.minecraft.world.level.ChunkPos.asLong(
                    target.getX() >> 4, target.getZ() >> 4));
            }
            work.run();
        });
    }
}

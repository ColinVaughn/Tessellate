package org.texboobcat.tessellate.region;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.texboobcat.tessellate.Config;
import org.texboobcat.tessellate.api.TessellateApi;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/** Internal routing for whole-tick compatibility fallbacks. */
public final class CompatibilityTicks {

    private static final Object ENTITY_TICK_LOCK = new Object();
    private static volatile boolean remoteEntitySerialization;

    private CompatibilityTicks() {
    }

    public static void tickEntity(Entity entity, Consumer<Entity> consumer) {
        Runnable tick = () -> runEntityTick(() -> {
            try {
                consumer.accept(entity);
            } catch (RuntimeException | Error failure) {
                if (RegionWorkers.isWorkerThread()) {
                    String entityTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(
                        entity.getType()).toString();
                    throw entityTickFailure(entityTypeId, failure);
                }
                throw failure;
            }
        });
        if (!deferEntity(entity.getType(), tick)) {
            tick.run();
        }
    }

    static RuntimeException entityTickFailure(String entityTypeId, Throwable failure) {
        return new TickFailure(entityTypeId, null, failure);
    }

    static RuntimeException blockEntityTickFailure(String blockEntityTypeId, Throwable failure) {
        return new TickFailure(null, blockEntityTypeId, failure);
    }

    @Nullable
    public static String failedEntityTypeId(@Nullable Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof TickFailure tickFailure) {
                return tickFailure.entityTypeId;
            }
        }
        return null;
    }

    @Nullable
    public static String failedBlockEntityTypeId(@Nullable Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof TickFailure tickFailure) {
                return tickFailure.blockEntityTypeId;
            }
        }
        return null;
    }

    public static void configureRemoteEntitySerialization(boolean enabled) {
        remoteEntitySerialization = enabled;
    }

    static void runEntityTick(Runnable tick) {
        if (!Config.serializeEntityTicks && !remoteEntitySerialization) {
            tick.run();
            return;
        }
        // A single lock is intentionally global because compatibility state may be shared by
        // unrelated entity types and regions.
        synchronized (ENTITY_TICK_LOCK) {
            tick.run();
        }
    }

    static boolean deferEntity(EntityType<?> type, Runnable tick) {
        if (!TessellateApi.requiresMainThreadEntityTick(type)
            || !RegionWorkers.isWorkerThread()) {
            return false;
        }
        DeferredMainThreadWork.deferGlobal(MainThreadBoundaries.Boundary.MOD_COMPATIBILITY, tick);
        return true;
    }

    public static boolean deferBlockEntity(BlockEntityType<?> type, Runnable tick) {
        if (!TessellateApi.requiresMainThreadBlockEntityTick(type)
            || !RegionWorkers.isWorkerThread()) {
            return false;
        }
        DeferredMainThreadWork.deferGlobal(MainThreadBoundaries.Boundary.MOD_COMPATIBILITY, tick);
        return true;
    }

    public static void tickBlockEntity(BlockEntityType<?> type, Runnable tick) {
        try {
            tick.run();
        } catch (RuntimeException | Error failure) {
            if (RegionWorkers.isWorkerThread()) {
                String blockEntityTypeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type)
                    .toString();
                throw blockEntityTickFailure(blockEntityTypeId, failure);
            }
            throw failure;
        }
    }

    private static final class TickFailure extends RuntimeException {

        @Nullable private final String entityTypeId;
        @Nullable private final String blockEntityTypeId;

        private TickFailure(@Nullable String entityTypeId, @Nullable String blockEntityTypeId,
                            Throwable cause) {
            super("tick failed for " + (entityTypeId == null ? blockEntityTypeId : entityTypeId),
                cause);
            this.entityTypeId = entityTypeId;
            this.blockEntityTypeId = blockEntityTypeId;
        }
    }
}

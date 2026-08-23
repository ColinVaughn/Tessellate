package org.texboobcat.tessellate.region;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.texboobcat.tessellate.Config;
import org.texboobcat.tessellate.api.TessellateApi;

import java.util.function.Consumer;

/** Internal routing for whole-tick compatibility fallbacks. */
public final class CompatibilityTicks {

    private static final Object ENTITY_TICK_LOCK = new Object();

    private CompatibilityTicks() {
    }

    public static void tickEntity(Entity entity, Consumer<Entity> consumer) {
        Runnable tick = () -> runEntityTick(() -> consumer.accept(entity));
        if (!deferEntity(entity.getType(), tick)) {
            tick.run();
        }
    }

    static void runEntityTick(Runnable tick) {
        if (!Config.serializeEntityTicks) {
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
}

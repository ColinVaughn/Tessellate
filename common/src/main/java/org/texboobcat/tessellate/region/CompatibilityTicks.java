package org.texboobcat.tessellate.region;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.texboobcat.tessellate.api.TessellateApi;

import java.util.function.Consumer;

/** Internal routing for whole-tick compatibility fallbacks. */
public final class CompatibilityTicks {

    private CompatibilityTicks() {
    }

    public static void tickEntity(Entity entity, Consumer<Entity> consumer) {
        Runnable tick = () -> consumer.accept(entity);
        if (!deferEntity(entity.getType(), tick)) {
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

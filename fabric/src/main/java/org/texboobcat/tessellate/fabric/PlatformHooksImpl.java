package org.texboobcat.tessellate.fabric;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.function.Consumer;

public final class PlatformHooksImpl {

    private PlatformHooksImpl() {
    }

    public static MobCategory getClassification(Entity entity, boolean forSpawnCount) {
        return entity.getType().getCategory();
    }

    public static void drainFreshBlockEntities(Level level, Consumer<BlockEntity> action) {
    }

    public static void onBlockEntityLoad(BlockEntity blockEntity) {
    }

    public static boolean shouldForceTicks(ServerLevel level, DistanceManager distanceManager,
                                           long chunkPos) {
        return level.getForcedChunks().contains(chunkPos);
    }
}

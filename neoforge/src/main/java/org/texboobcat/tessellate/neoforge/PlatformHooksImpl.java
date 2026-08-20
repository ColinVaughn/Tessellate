package org.texboobcat.tessellate.neoforge;

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
        return entity.getClassification(forSpawnCount);
    }

    public static void drainFreshBlockEntities(Level level, Consumer<BlockEntity> action) {
        ((LevelAccess) level).tessellate$drainFreshBlockEntities(action);
    }

    public static void onBlockEntityLoad(BlockEntity blockEntity) {
        blockEntity.onLoad();
    }

    public static boolean shouldForceTicks(ServerLevel level, DistanceManager distanceManager,
                                           long chunkPos) {
        return distanceManager.shouldForceTicks(chunkPos);
    }

    public interface LevelAccess {
        void tessellate$drainFreshBlockEntities(Consumer<BlockEntity> action);
    }
}

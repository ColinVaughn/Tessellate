package org.texboobcat.tessellate;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.function.Consumer;

public final class PlatformHooks {

    private PlatformHooks() {
    }

    @ExpectPlatform
    public static MobCategory getClassification(Entity entity, boolean forSpawnCount) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static void drainFreshBlockEntities(Level level, Consumer<BlockEntity> action) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static void onBlockEntityLoad(BlockEntity blockEntity) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static boolean shouldForceTicks(ServerLevel level, DistanceManager distanceManager,
                                           long chunkPos) {
        throw new AssertionError();
    }
}

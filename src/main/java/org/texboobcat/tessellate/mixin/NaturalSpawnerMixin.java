package org.texboobcat.tessellate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.texboobcat.tessellate.region.ParallelNaturalSpawner;

// Takes the cap reservation only after vanilla and NeoForge accept the mob, but before
// finalizeSpawn or entity insertion can have side effects.
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {

    @WrapOperation(
        method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;"
            + "Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;"
            + "Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/NaturalSpawner;"
                + "isValidPositionForMob(Lnet/minecraft/server/level/ServerLevel;"
                + "Lnet/minecraft/world/entity/Mob;D)Z"))
    private static boolean tessellate$reserveAcceptedSpawn(ServerLevel level, Mob mob,
                                                         double distance,
                                                         Operation<Boolean> original) {
        boolean accepted = original.call(level, mob, distance);
        return accepted && (!ParallelNaturalSpawner.active()
            || ParallelNaturalSpawner.reserve(mob));
    }
}

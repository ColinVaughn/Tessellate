package org.texboobcat.optimal.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.texboobcat.optimal.region.ParallelNaturalSpawner;

// Substitutes the shared SpawnState's mutable callbacks only inside a parallel spawn session.
@Mixin(NaturalSpawner.SpawnState.class)
public abstract class NaturalSpawnerSpawnStateMixin {

    @Inject(method = "canSpawn", at = @At("HEAD"), cancellable = true)
    private void optimal$checkParallelPotential(EntityType<?> type, BlockPos pos,
                                                 ChunkAccess chunk,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (ParallelNaturalSpawner.active()) {
            cir.setReturnValue(ParallelNaturalSpawner.canSpawn(type, pos, chunk));
        }
    }

    @Inject(method = "afterSpawn", at = @At("HEAD"), cancellable = true)
    private void optimal$commitParallelReservation(Mob mob, ChunkAccess chunk, CallbackInfo ci) {
        if (ParallelNaturalSpawner.active()) {
            NaturalSpawnerSpawnStateInvoker self =
                (NaturalSpawnerSpawnStateInvoker) (Object) this;
            ParallelNaturalSpawner.afterSpawn(mob,
                () -> self.optimal$afterSpawn(mob, chunk));
            ci.cancel();
        }
    }

    @Inject(method = "canSpawnForCategory", at = @At("HEAD"), cancellable = true)
    private void optimal$checkParallelCaps(MobCategory category, ChunkPos chunk,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (ParallelNaturalSpawner.active()) {
            cir.setReturnValue(ParallelNaturalSpawner.canSpawnForCategory(category, chunk));
        }
    }
}

package org.texboobcat.optimal.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NaturalSpawner.SpawnState.class)
public interface NaturalSpawnerSpawnStateInvoker {

    @Invoker("afterSpawn")
    void optimal$afterSpawn(Mob mob, ChunkAccess chunk);
}

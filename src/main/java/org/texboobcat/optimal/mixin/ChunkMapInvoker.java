package org.texboobcat.optimal.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {

    @Invoker("anyPlayerCloseEnoughForSpawning")
    boolean optimal$anyPlayerCloseEnoughForSpawning(ChunkPos chunkPos);
}

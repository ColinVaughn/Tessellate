package org.texboobcat.tessellate.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {

    @Invoker("getChunks")
    Iterable<ChunkHolder> tessellate$getChunks();

    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder tessellate$getVisibleChunkIfPresent(long chunkPos);

    @Invoker("anyPlayerCloseEnoughForSpawning")
    boolean tessellate$anyPlayerCloseEnoughForSpawning(ChunkPos chunkPos);
}

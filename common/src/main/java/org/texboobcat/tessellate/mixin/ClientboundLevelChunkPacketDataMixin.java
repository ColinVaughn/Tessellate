package org.texboobcat.tessellate.mixin;

import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.texboobcat.tessellate.Config;
import org.texboobcat.tessellate.region.LevelRegionIndex;
import org.texboobcat.tessellate.region.RegionTracker;
import org.texboobcat.tessellate.region.RegionWorkers;

@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ClientboundLevelChunkPacketDataMixin {
    // Lease before sizing or copying any chunk data, while the server controls dispatch.
    @Inject(method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At(value = "CTOR_HEAD", unsafe = true, args = "enforce=POST_DELEGATE"))
    private void tessellate$leaseChunkSnapshot(LevelChunk chunk, CallbackInfo ci) {
        if (!Config.asyncRegionLoops || RegionWorkers.isWorkerThread()
            || !(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        LevelRegionIndex index = RegionTracker.index(level);
        if (index != null) {
            index.leaseChunk(chunk.getPos().toLong());
        }
    }
}

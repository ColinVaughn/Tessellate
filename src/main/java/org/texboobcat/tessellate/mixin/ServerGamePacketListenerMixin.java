package org.texboobcat.tessellate.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.texboobcat.tessellate.Config;
import org.texboobcat.tessellate.region.LevelRegionIndex;
import org.texboobcat.tessellate.region.RegionTracker;

// The connection tick calls ServerPlayer.doTick; keep it out of a claimed region.
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "tick", at = @At("HEAD"))
    private void tessellate$leasePlayerTick(CallbackInfo ci) {
        if (!Config.asyncRegionLoops) {
            return;
        }
        LevelRegionIndex index = RegionTracker.index(this.player.serverLevel());
        if (index != null) {
            index.leaseChunk(this.player.chunkPosition().toLong());
        }
    }
}

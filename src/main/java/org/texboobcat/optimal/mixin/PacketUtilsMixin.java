package org.texboobcat.optimal.mixin;

import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.texboobcat.optimal.Config;
import org.texboobcat.optimal.region.LevelRegionIndex;
import org.texboobcat.optimal.region.RegionTracker;

// A main-thread player packet leases the player's region before its handler continues.
@Mixin(PacketUtils.class)
public abstract class PacketUtilsMixin {

    @Inject(
        method = "ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;"
            + "Lnet/minecraft/network/PacketListener;"
            + "Lnet/minecraft/util/thread/BlockableEventLoop;)V",
        at = @At("RETURN"))
    private static void optimal$leasePlayerPacket(Packet<?> packet, PacketListener listener,
                                                   BlockableEventLoop<?> loop, CallbackInfo ci) {
        if (!Config.asyncRegionLoops
            || !(listener instanceof ServerGamePacketListenerImpl game)) {
            return;
        }
        LevelRegionIndex index = RegionTracker.index(game.player.serverLevel());
        if (index != null) {
            index.leaseChunk(game.player.chunkPosition().toLong());
        }
    }
}

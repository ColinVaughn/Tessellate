package org.texboobcat.optimal.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.texboobcat.optimal.Config;
import org.texboobcat.optimal.region.LevelRegionIndex;
import org.texboobcat.optimal.region.RegionTracker;

// Raid bookkeeping stays main-threaded and leases the spatial region around its center.
@Mixin(Raid.class)
public abstract class RaidMixin {

    @Shadow
    private BlockPos center;

    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "tick", at = @At("HEAD"))
    private void optimal$leaseRaidRegion(CallbackInfo ci) {
        if (!Config.asyncRegionLoops) {
            return;
        }
        LevelRegionIndex index = RegionTracker.index(this.level);
        if (index != null) {
            index.leaseChunk(net.minecraft.world.level.ChunkPos.asLong(
                this.center.getX() >> 4, this.center.getZ() >> 4));
        }
    }
}

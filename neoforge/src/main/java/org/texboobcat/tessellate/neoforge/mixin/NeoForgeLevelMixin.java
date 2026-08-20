package org.texboobcat.tessellate.neoforge.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.texboobcat.tessellate.Config;
import org.texboobcat.tessellate.neoforge.PlatformHooksImpl;
import org.texboobcat.tessellate.region.DeferredMainThreadWork;
import org.texboobcat.tessellate.region.LevelRegionIndex;
import org.texboobcat.tessellate.region.MainThreadBoundaries;
import org.texboobcat.tessellate.region.RegionWorkers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

@Mixin(Level.class)
public abstract class NeoForgeLevelMixin implements PlatformHooksImpl.LevelAccess {

    @Shadow
    @Final
    private ArrayList<BlockEntity> freshBlockEntities;

    @Shadow
    @Final
    private ArrayList<BlockEntity> pendingFreshBlockEntities;

    @Inject(method = "addFreshBlockEntities", at = @At("HEAD"), cancellable = true)
    private void tessellate$deferFreshBlockEntities(Collection<BlockEntity> blockEntities,
                                                    CallbackInfo ci) {
        Level level = (Level) (Object) this;
        if (!RegionWorkers.isWorkerThread()) {
            if (Config.asyncRegionLoops) {
                LevelRegionIndex.RegionalLevelAccess access =
                    (LevelRegionIndex.RegionalLevelAccess) level;
                for (BlockEntity blockEntity : blockEntities) {
                    access.tessellate$leasePos(blockEntity.getBlockPos());
                }
            }
            return;
        }
        List<BlockEntity> copy = List.copyOf(blockEntities);
        DeferredMainThreadWork.defer(
            MainThreadBoundaries.Boundary.BLOCK_ENTITY_REGISTRATION,
            () -> level.addFreshBlockEntities(copy));
        ci.cancel();
    }

    @Override
    public void tessellate$drainFreshBlockEntities(Consumer<BlockEntity> action) {
        if (!this.pendingFreshBlockEntities.isEmpty()) {
            this.freshBlockEntities.addAll(this.pendingFreshBlockEntities);
            this.pendingFreshBlockEntities.clear();
        }
        this.freshBlockEntities.forEach(action);
        this.freshBlockEntities.clear();
    }
}

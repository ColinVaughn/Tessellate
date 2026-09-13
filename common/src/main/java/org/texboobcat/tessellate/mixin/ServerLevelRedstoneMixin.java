package org.texboobcat.tessellate.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.texboobcat.tessellate.region.RedstoneUpdates;
import org.texboobcat.tessellate.region.RegionWorkers;

@Mixin(ServerLevel.class)
public abstract class ServerLevelRedstoneMixin {
    @Shadow
    protected abstract void tickBlock(BlockPos pos, Block block);

    // NeoForge reads the source for its event hook before reaching the neighbor updater.
    @Inject(method = "updateNeighborsAt", at = @At("HEAD"), cancellable = true)
    private void tessellate$deferNeighbors(BlockPos pos, Block block, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (!RedstoneUpdates.canRun(level, pos)) {
            BlockPos source = pos.immutable();
            RedstoneUpdates.defer(() -> level.updateNeighborsAt(source, block));
            ci.cancel();
        }
    }

    @Inject(method = "updateNeighborsAtExceptFromFacing", at = @At("HEAD"), cancellable = true)
    private void tessellate$deferNeighborsExcept(BlockPos pos, Block block, Direction facing, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (!RedstoneUpdates.canRun(level, pos)) {
            BlockPos source = pos.immutable();
            RedstoneUpdates.defer(() -> level.updateNeighborsAtExceptFromFacing(source, block, facing));
            ci.cancel();
        }
    }

    @Inject(method = "tickBlock", at = @At("HEAD"), cancellable = true)
    private void tessellate$deferRedstoneTick(BlockPos pos, Block block, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (RegionWorkers.isWorkerThread()
            && (block instanceof RedstoneTorchBlock || block instanceof DiodeBlock || block instanceof ObserverBlock)
            && !RedstoneUpdates.canRun(level, pos)) {
            BlockPos target = pos.immutable();
            RedstoneUpdates.defer(() -> this.tickBlock(target, block));
            ci.cancel();
        }
    }
}

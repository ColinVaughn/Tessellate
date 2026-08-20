package org.texboobcat.tessellate.mixin.accessor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(WalkNodeEvaluator.class)
public interface WalkNodeEvaluatorInvoker {

    @Invoker("getPathTypeFromState")
    static PathType tessellate$getPathTypeFromState(BlockGetter level, BlockPos pos) {
        throw new AssertionError();
    }
}

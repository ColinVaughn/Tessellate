package org.texboobcat.tessellate.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RedStoneWireBlock.class)
public abstract class RedStoneWireBlockMixin {
    @Unique
    private final ThreadLocal<Boolean> tessellate$shouldSignal = ThreadLocal.withInitial(() -> true);

    @Redirect(method = "calculateTargetStrength", at = @At(value = "FIELD",
        target = "Lnet/minecraft/world/level/block/RedStoneWireBlock;shouldSignal:Z", opcode = Opcodes.PUTFIELD))
    private void tessellate$setSignal(RedStoneWireBlock wire, boolean value) {
        this.tessellate$shouldSignal.set(value);
    }

    @Redirect(method = {"getSignal", "getDirectSignal", "isSignalSource"}, at = @At(value = "FIELD",
        target = "Lnet/minecraft/world/level/block/RedStoneWireBlock;shouldSignal:Z", opcode = Opcodes.GETFIELD))
    private boolean tessellate$getSignal(RedStoneWireBlock wire) {
        return this.tessellate$shouldSignal.get();
    }

    @WrapMethod(method = "calculateTargetStrength")
    private int tessellate$restoreSignal(Level level, BlockPos pos, Operation<Integer> original) {
        boolean previous = this.tessellate$shouldSignal.get();
        try {
            return original.call(level, pos);
        } finally {
            this.tessellate$shouldSignal.set(previous);
        }
    }
}

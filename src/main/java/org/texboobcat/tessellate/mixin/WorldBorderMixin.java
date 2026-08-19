package org.texboobcat.tessellate.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;

// Region block-entity ticks can concurrently register Lithium's one-shot border listeners.
@Mixin(WorldBorder.class)
public abstract class WorldBorderMixin {

    @WrapMethod(method = "addListener")
    private void tessellate$serializeListenerRegistration(BorderChangeListener listener, Operation<Void> original) {
        synchronized (this) {
            original.call(listener);
        }
    }
}

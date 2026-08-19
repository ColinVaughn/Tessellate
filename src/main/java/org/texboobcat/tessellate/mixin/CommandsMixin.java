package org.texboobcat.tessellate.mixin;

import com.mojang.brigadier.ParseResults;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.texboobcat.tessellate.Config;
import org.texboobcat.tessellate.region.RegionWorkers;
import org.texboobcat.tessellate.region.RegionTracker;
import org.texboobcat.tessellate.region.MainThreadBoundaries;

// Commands can target arbitrary dimensions and selectors, so execution is an explicit barrier.
@Mixin(Commands.class)
public abstract class CommandsMixin {

    @WrapMethod(method = "performCommand")
    private void tessellate$runCommandBoundary(ParseResults<CommandSourceStack> parsed,
                                            String command, Operation<Void> original) {
        if (Config.regionsEnabled && !RegionWorkers.isWorkerThread()) {
            MainThreadBoundaries.measure(MainThreadBoundaries.Boundary.COMMAND_BARRIER,
                "global/main", () -> {
                    RegionTracker.quiesceAndDrain();
                    original.call(parsed, command);
                });
            return;
        }
        original.call(parsed, command);
    }
}

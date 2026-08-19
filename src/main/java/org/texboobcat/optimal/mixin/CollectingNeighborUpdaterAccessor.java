package org.texboobcat.optimal.mixin;

import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Reads the chain limit off the updater vanilla already built.
//
// Cheaper and less brittle than capturing it from the Level constructor's parameter
// list, which would have to be matched positionally against a signature that changes between
// versions.
@Mixin(CollectingNeighborUpdater.class)
public interface CollectingNeighborUpdaterAccessor {

    @Accessor("maxChainedNeighborUpdates")
    int optimal$getMaxChainedNeighborUpdates();
}

package org.texboobcat.optimal.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.texboobcat.optimal.Config;
import org.texboobcat.optimal.region.DeferredMainThreadWork;
import org.texboobcat.optimal.region.MainThreadBoundaries;
import org.texboobcat.optimal.region.RegionWorkers;

// NeoForge's section-change hook is an event boundary, so workers hand it to the main thread.
@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback")
public abstract class PersistentEntityCallbackMixin {

    @Redirect(
        method = "onMove",
        at = @At(
            value = "INVOKE",
            target = "Lnet/neoforged/neoforge/common/CommonHooks;"
                + "onEntityEnterSection(Lnet/minecraft/world/entity/Entity;JJ)V"))
    private void optimal$deferSectionEvent(Entity entity, long oldSection, long newSection) {
        if (Config.asyncRegionLoops && RegionWorkers.isWorkerThread()) {
            DeferredMainThreadWork.defer(MainThreadBoundaries.Boundary.ENTITY_LIFECYCLE, () ->
                net.neoforged.neoforge.common.CommonHooks.onEntityEnterSection(
                    entity, oldSection, newSection));
            return;
        }
        net.neoforged.neoforge.common.CommonHooks.onEntityEnterSection(
            entity, oldSection, newSection);
    }
}

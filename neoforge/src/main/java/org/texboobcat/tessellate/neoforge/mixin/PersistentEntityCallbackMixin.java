package org.texboobcat.tessellate.neoforge.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.texboobcat.tessellate.Config;
import org.texboobcat.tessellate.region.DeferredMainThreadWork;
import org.texboobcat.tessellate.region.MainThreadBoundaries;
import org.texboobcat.tessellate.region.RegionWorkers;

// NeoForge's section-change hook is an event boundary, so workers hand it to the main thread.
@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback")
public abstract class PersistentEntityCallbackMixin {

    @Redirect(
        method = "onMove",
        at = @At(
            value = "INVOKE",
            target = "Lnet/neoforged/neoforge/common/CommonHooks;"
                + "onEntityEnterSection(Lnet/minecraft/world/entity/Entity;JJ)V"))
    private void tessellate$deferSectionEvent(Entity entity, long oldSection, long newSection) {
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

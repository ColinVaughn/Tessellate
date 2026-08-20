package org.texboobcat.tessellate.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.texboobcat.tessellate.Tessellate;
import org.texboobcat.tessellate.client.RegionOverlay;

@EventBusSubscriber(modid = Tessellate.MODID, value = Dist.CLIENT)
public final class NeoForgeRegionRenderEvents {

    private NeoForgeRegionRenderEvents() {
    }

    @SubscribeEvent
    public static void renderWorld(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            RegionOverlay.renderWorld(event.getPoseStack(), event.getCamera(), event.getPartialTick());
        }
    }
}

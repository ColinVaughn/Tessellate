package org.texboobcat.tessellate.fabric;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import org.texboobcat.tessellate.client.RegionOverlay;

public final class FabricRegionRenderEvents {

    private FabricRegionRenderEvents() {
    }

    public static void init() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> RegionOverlay.renderWorld(
            context.matrixStack(), context.camera(), context.tickCounter()));
    }
}

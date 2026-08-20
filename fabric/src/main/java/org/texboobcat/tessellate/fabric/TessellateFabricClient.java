package org.texboobcat.tessellate.fabric;

import net.fabricmc.api.ClientModInitializer;
import org.texboobcat.tessellate.client.TessellateClient;

public final class TessellateFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        TessellateClient.init();
        FabricRegionRenderEvents.init();
    }
}

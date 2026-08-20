package org.texboobcat.tessellate.fabric;

import net.fabricmc.api.ModInitializer;
import org.texboobcat.tessellate.Tessellate;

public final class TessellateFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        TessellateFabricConfig.load();
        Tessellate.init();
    }
}

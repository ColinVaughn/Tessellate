package org.texboobcat.tessellate.network;

import dev.architectury.networking.NetworkManager;

public final class TessellateNetwork {

    private TessellateNetwork() {
    }

    public static void registerServerPayload() {
        NetworkManager.registerS2CPayloadType(
            RegionVisualizationPayload.TYPE, RegionVisualizationPayload.STREAM_CODEC);
    }
}

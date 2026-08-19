package org.texboobcat.optimal.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.texboobcat.optimal.client.RegionOverlay;

// The one optional channel used by the visualizer; vanilla clients retain the particle fallback.
public final class OptimalNetwork {

    private OptimalNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(
            RegionVisualizationPayload.TYPE,
            RegionVisualizationPayload.STREAM_CODEC,
            (payload, context) -> RegionOverlay.accept(payload));
    }
}

package org.texboobcat.tessellate;

import com.mojang.logging.LogUtils;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import org.slf4j.Logger;
import org.texboobcat.tessellate.command.TessellateCommand;
import org.texboobcat.tessellate.network.TessellateNetwork;
import org.texboobcat.tessellate.region.RegionTracker;
import org.texboobcat.tessellate.region.RegionWorkers;

public final class Tessellate {

    public static final String MODID = "tessellate";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean initialized;

    private Tessellate() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        if (Platform.getEnvironment() == Env.SERVER) {
            TessellateNetwork.registerServerPayload();
        }

        LifecycleEvent.SERVER_LEVEL_LOAD.register(serverLevel -> {
            RegionTracker.onLevelLoad(serverLevel);
            LOGGER.info("tessellate: tracking regions in {}", serverLevel.dimension().location());
        });
        LifecycleEvent.SERVER_LEVEL_UNLOAD.register(RegionTracker::onLevelUnload);

        TickEvent.SERVER_LEVEL_PRE.register(serverLevel -> {
            RegionTracker.onLevelTick(serverLevel);
            TessellateCommand.tickVisualization(serverLevel);
        });

        LifecycleEvent.SERVER_STARTED.register(server -> {
            if (Config.regionsEnabled && Config.parallelTickingConfigured()) {
                RegionWorkers.start();
                LOGGER.info("tessellate: independent region ticking enabled; the server will fall "
                    + "back to serial ticking for this session on the first unsafe access.");
            }
        });

        LifecycleEvent.SERVER_STOPPED.register(server -> {
            RegionWorkers.stop();
            RegionTracker.reset();
        });

        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) ->
            TessellateCommand.register(dispatcher));
    }
}

package org.texboobcat.tessellate;

import com.mojang.logging.LogUtils;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import org.slf4j.Logger;
import org.texboobcat.tessellate.api.TessellateApiInternal;
import org.texboobcat.tessellate.command.TessellateCommand;
import org.texboobcat.tessellate.network.TessellateNetwork;
import org.texboobcat.tessellate.region.CompatibilityTicks;
import org.texboobcat.tessellate.region.ParallelNaturalSpawner;
import org.texboobcat.tessellate.region.RegionTracker;
import org.texboobcat.tessellate.region.RegionWorkers;

import java.util.TreeSet;

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

        LifecycleEvent.SERVER_STARTED.register(server -> applyRemoteCompatibilityRules());

        LifecycleEvent.SERVER_STOPPED.register(server -> {
            RegionWorkers.stop();
            RegionTracker.reset();
            TessellateApiInternal.configureRemoteMainThreadEntities(java.util.List.of());
            TessellateApiInternal.configureRemoteMainThreadBlockEntities(java.util.List.of());
            CompatibilityTicks.configureRemoteEntitySerialization(false);
        });

        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) ->
            TessellateCommand.register(dispatcher));
    }

    private static void applyRemoteCompatibilityRules() {
        var remoteRules = CompatibilityReporter.loadRemoteRules();
        TessellateApiInternal.configureRemoteMainThreadEntities(remoteRules.mainThreadEntities());
        TessellateApiInternal.configureRemoteMainThreadBlockEntities(
            remoteRules.mainThreadBlockEntities());
        CompatibilityTicks.configureRemoteEntitySerialization(
            !remoteRules.serializeEntityTickMods().isEmpty());
        if (!remoteRules.mainThreadEntities().isEmpty()) {
            LOGGER.info("tessellate: applying {} remote main-thread entity exclusion(s): {}",
                remoteRules.mainThreadEntities().size(),
                String.join(", ", remoteRules.mainThreadEntities()));
        }
        if (!remoteRules.mainThreadBlockEntities().isEmpty()) {
            LOGGER.info("tessellate: applying {} remote main-thread block-entity exclusion(s): {}",
                remoteRules.mainThreadBlockEntities().size(),
                String.join(", ", remoteRules.mainThreadBlockEntities()));
        }
        if (!remoteRules.serializeEntityTickMods().isEmpty()) {
            LOGGER.info("tessellate: serializing entity ticks for loaded mod(s): {}",
                String.join(", ", remoteRules.serializeEntityTickMods()));
        }
        if (!remoteRules.serialNaturalSpawningMods().isEmpty()) {
            ParallelNaturalSpawner.forceSerial("remote compatibility override for loaded mod(s): "
                + String.join(", ", remoteRules.serialNaturalSpawningMods()));
            LOGGER.info("tessellate: using serial natural spawning for loaded mod(s): {}",
                String.join(", ", remoteRules.serialNaturalSpawningMods()));
        }
        if (Config.regionsEnabled && Config.parallelTickingConfigured()) {
            applySerialRegionRules(remoteRules);
        }
    }

    private static void applySerialRegionRules(CompatibilityReporter.RemoteRules remoteRules) {
        var forcedSerialMods = new TreeSet<>(CompatibilityReporter.loadedForcedSerialMods());
        forcedSerialMods.addAll(remoteRules.serialRegionMods());
        if (forcedSerialMods.isEmpty()) {
            RegionWorkers.start();
            LOGGER.info("tessellate: independent region ticking enabled; the server will fall "
                + "back to serial ticking for this session on the first unsafe access.");
        } else {
            RegionTracker.forceSerial("configured compatibility override for loaded mod(s): "
                + String.join(", ", forcedSerialMods));
        }
    }
}

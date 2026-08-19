package org.texboobcat.optimal;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;
import org.texboobcat.optimal.command.OptimalCommand;
import org.texboobcat.optimal.network.OptimalNetwork;
import org.texboobcat.optimal.region.RegionTracker;
import org.texboobcat.optimal.region.RegionWorkers;

@Mod(Optimal.MODID)
public class Optimal {

    public static final String MODID = "optimal";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Optimal(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(Config::onLoad);
        modEventBus.addListener(OptimalNetwork::register);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            RegionTracker.onLevelLoad(serverLevel);
            LOGGER.info("optimal: tracking regions in {}", serverLevel.dimension().location());
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            RegionTracker.onLevelUnload(serverLevel);
        }
    }

    // Runs before any of the level's ticking subsystems, which is where the regionizer must apply
    // queued merges and splits so that region identity is stable for the whole tick.
    @SubscribeEvent
    public void onLevelTickPre(LevelTickEvent.Pre event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            RegionTracker.onLevelTick(serverLevel);
            OptimalCommand.tickVisualization(serverLevel);
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (Config.regionsEnabled && Config.parallelTickingConfigured()) {
            RegionWorkers.start();
            LOGGER.info("optimal: independent region ticking enabled; the server will fall back "
                + "to serial ticking for this session on the first unsafe access.");
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        RegionWorkers.stop();
        RegionTracker.reset();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        OptimalCommand.register(event.getDispatcher());
    }
}

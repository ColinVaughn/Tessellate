package org.texboobcat.tessellate;

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
import org.texboobcat.tessellate.command.TessellateCommand;
import org.texboobcat.tessellate.network.TessellateNetwork;
import org.texboobcat.tessellate.region.RegionTracker;
import org.texboobcat.tessellate.region.RegionWorkers;

@Mod(Tessellate.MODID)
public class Tessellate {

    public static final String MODID = "tessellate";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Tessellate(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(Config::onLoad);
        modEventBus.addListener(TessellateNetwork::register);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            RegionTracker.onLevelLoad(serverLevel);
            LOGGER.info("tessellate: tracking regions in {}", serverLevel.dimension().location());
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
            TessellateCommand.tickVisualization(serverLevel);
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (Config.regionsEnabled && Config.parallelTickingConfigured()) {
            RegionWorkers.start();
            LOGGER.info("tessellate: independent region ticking enabled; the server will fall back "
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
        TessellateCommand.register(event.getDispatcher());
    }
}

package org.texboobcat.optimal.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.joml.Vector3f;
import org.texboobcat.optimal.Config;
import org.texboobcat.optimal.guard.ViolationLog;
import org.texboobcat.optimal.network.RegionVisualizationPayload;
import org.texboobcat.optimal.region.LevelRegionIndex;
import org.texboobcat.optimal.region.PhaseStats;
import org.texboobcat.optimal.region.MainThreadBoundaries;
import org.texboobcat.optimal.region.Region;
import org.texboobcat.optimal.region.RegionSectionPos;
import org.texboobcat.optimal.region.RegionTickState;
import org.texboobcat.optimal.region.RegionTracker;
import org.texboobcat.optimal.region.RegionWorkers;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Inspection commands.
//
// /optimal regions is the visible proof that partitioning matches the world; it is what
// the region benchmark checks before measuring anything.
public final class OptimalCommand {

    private static final int MAX_REGIONS_SHOWN = 8;
    private static final int MAX_VIOLATION_SITES_SHOWN = 10;
    private static final Set<UUID> VISUALIZERS = new HashSet<>();
    private static final DustParticleOptions CHUNK_PARTICLE = dust(1.0F, 1.0F, 1.0F);
    private static final DustParticleOptions IDLE_PARTICLE = dust(0.45F, 0.45F, 0.45F);
    private static final DustParticleOptions[] WORKER_PARTICLES = {
        dust(1.0F, 0.2F, 0.2F), dust(0.2F, 1.0F, 0.2F),
        dust(0.25F, 0.45F, 1.0F), dust(1.0F, 0.85F, 0.15F),
        dust(1.0F, 0.2F, 1.0F), dust(0.1F, 1.0F, 1.0F),
        dust(1.0F, 0.5F, 0.1F), dust(0.65F, 1.0F, 0.1F),
        dust(0.55F, 0.25F, 1.0F), dust(1.0F, 0.35F, 0.65F),
        dust(0.1F, 0.75F, 0.55F), dust(0.2F, 0.75F, 1.0F),
        dust(0.75F, 0.55F, 0.1F), dust(0.4F, 1.0F, 0.55F),
        dust(0.75F, 0.2F, 0.85F), dust(1.0F, 0.4F, 0.3F)
    };

    private OptimalCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("optimal")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("regions")
                .executes(ctx -> regions(ctx.getSource(), false))
                .then(Commands.literal("verbose")
                    .executes(ctx -> regions(ctx.getSource(), true))))
            .then(Commands.literal("violations")
                .executes(ctx -> violations(ctx.getSource()))
                .then(Commands.literal("clear")
                    .executes(ctx -> clearViolations(ctx.getSource()))))
            .then(Commands.literal("phases")
                .executes(ctx -> phases(ctx.getSource())))
            .then(Commands.literal("visualize")
                .executes(ctx -> toggleVisualization(ctx.getSource()))
                .then(Commands.literal("on")
                    .executes(ctx -> setVisualization(ctx.getSource(), true)))
                .then(Commands.literal("off")
                    .executes(ctx -> setVisualization(ctx.getSource(), false)))));
    }

    private static int regions(CommandSourceStack source, boolean verbose) {
        if (!Config.regionsEnabled) {
            source.sendSuccess(() -> Component.literal("optimal: region tracking is disabled"), false);
            return 0;
        }

        String mode = executionMode();
        source.sendSuccess(() -> Component.literal("execution: " + mode), false);
        sendStorageSummary(source);
        sendDeferredSummary(source);
        source.sendSuccess(() -> Component.literal("autosave: "
            + RegionTracker.deferredAutosaves() + " busy-region attempt(s) deferred"), false);

        int totalRegions = 0;
        for (LevelRegionIndex index : RegionTracker.indexes()) {
            totalRegions += sendIndexSummary(source, index, verbose);
        }

        final int total = totalRegions;
        source.sendSuccess(() -> Component.literal("total: " + total + " region(s)"), false);
        return total;
    }

    private static String executionMode() {
        if (!Config.parallelTicking) {
            return "serial (regions.parallelTicking=false)";
        }
        if (!Config.parallelTickingConfigured()) {
            return "serial (shardEntityStorage and threadLocalRandom are required)";
        }
        if (!RegionTracker.parallelAllowed()) {
            return "serial - DEGRADED: " + RegionTracker.degradeReason();
        }
        if (!RegionWorkers.isRunning()) {
            return "serial (workers not running)";
        }
        return (Config.asyncRegionLoops ? "async (independent region loops)" : "staged (barrier)")
            + " on " + RegionWorkers.poolSize() + " worker(s), direct chunk reads "
            + (Config.directWorkerChunkReads ? "on" : "off");
    }

    private static void sendStorageSummary(CommandSourceStack source) {
        // Reported so the differential storage check can tell which build it is talking to
        // rather than trusting the config file it was pointed at.
        StringBuilder storage = new StringBuilder("storage: shardEntityStorage=")
            .append(Config.shardEntityStorage);
        if (Config.shardEntityStorage) {
            storage.append(", ")
                .append(org.texboobcat.optimal.region.RegionShardedEntityStorage.liveInstances())
                .append(" storage(s), ")
                .append(org.texboobcat.optimal.region.RegionShardedEntityStorage.totalShards())
                .append(" shard(s)");
        }
        String storageLine = storage.toString();
        source.sendSuccess(() -> Component.literal(storageLine), false);
    }

    private static void sendDeferredSummary(CommandSourceStack source) {
        // Reported because deferral silently ceasing looks exactly like deferral working.
        long callbacksDeferred = 0;
        long callbacksReplayed = 0;
        for (LevelRegionIndex index : RegionTracker.indexes()) {
            callbacksDeferred += index.deferredCallbacks().deferredCount();
            callbacksReplayed += index.deferredCallbacks().replayedCount();
        }
        final String deferLine = String.format(
            "deferred to main thread: %d/%d entity callback(s), %d/%d level write(s) replayed",
            callbacksReplayed, callbacksDeferred,
            org.texboobcat.optimal.region.DeferredMainThreadWork.replayedCount(),
            org.texboobcat.optimal.region.DeferredMainThreadWork.deferredCount());
        source.sendSuccess(() -> Component.literal(deferLine), false);
    }

    private static int sendIndexSummary(CommandSourceStack source, LevelRegionIndex index,
                                        boolean verbose) {
        List<Region> regions = new ArrayList<>(index.regionizer().regions());
        regions.sort(Comparator.comparingInt(Region::sectionCount).reversed());

        source.sendSuccess(() -> Component.literal(String.format(
            "%s: %d region(s), %d ticking chunk(s), update %.3f ms, "
                + "verify %.3f ms (peak %.3f, drift %d)",
            index.levelKey(),
            index.regionizer().regionCount(),
            index.tickingChunkCount(),
            index.lastUpdateNanos() / 1_000_000.0,
            index.lastVerifyNanos() / 1_000_000.0,
            index.peakVerifyNanos() / 1_000_000.0,
            index.discrepancies())), false);

        int shift = index.regionizer().sectionShift();
        source.sendSuccess(() -> Component.literal(String.format(
            "  sectionShift=%d (%dx%d chunks), mergeRadius=%d, merges=%d, splits=%d",
            shift,
            1 << shift,
            1 << shift,
            index.regionizer().mergeRadius(),
            index.regionizer().mergeCount(),
            index.regionizer().splitCount())), false);

        source.sendSuccess(() -> Component.literal(String.format(
            "  entities: scoped=%s, %.3f ms total, %d orphan(s), %d misplaced (repaired)",
            Config.scopedEntityTicking,
            index.lastEntityTickNanos() / 1_000_000.0,
            index.orphanEntityCount(),
            index.entityDiscrepancies())), false);

        source.sendSuccess(() -> Component.literal(String.format(
            "  blockentities: scoped=%s, %.3f ms grouping, %d orphan(s)",
            Config.scopedBlockEntityTicking,
            index.lastBlockEntityGroupNanos() / 1_000_000.0,
            index.orphanBlockEntityCount())), false);

        source.sendSuccess(() -> Component.literal(String.format(
            "  throttle: %s, budget floor %.1f ms, %d region(s) throttled",
            Config.adaptiveThrottling ? "on" : "off",
            Config.regionBudgetMillis,
            index.lastThrottledRegions())), false);

        source.sendSuccess(() -> Component.literal(String.format(
            "  parallel: %d region(s) dispatched, %d still busy (skipped), "
                + "%d unavailable-chunk miss(es)",
            index.lastParallelRegions(),
            index.lastSkippedRegions(),
            RegionTracker.unavailableChunks())), false);

        int shown = 0;
        for (Region region : regions) {
            if (!verbose && shown >= MAX_REGIONS_SHOWN) {
                int remaining = regions.size() - shown;
                source.sendSuccess(() -> Component.literal("  ... " + remaining + " more"), false);
                break;
            }
            shown++;
            sendRegionSummary(source, index, region, shift);
        }
        return regions.size();
    }

    private static void sendRegionSummary(CommandSourceStack source, LevelRegionIndex index,
                                          Region region, int shift) {
        long anySection = region.sections().iterator().nextLong();
        // section coord -> chunk coord -> block coord
        int blockX = RegionSectionPos.x(anySection) << (shift + 4);
        int blockZ = RegionSectionPos.z(anySection) << (shift + 4);
        RegionTickState state = index.stateFor(region);
        int entities = state == null ? 0 : state.entityCount();
        int blockEntities = state == null ? 0 : state.blockEntityCount();
        source.sendSuccess(() -> Component.literal(String.format(
            "  region#%d: %d section(s), %d entity(s) %.3f ms, %d blockentity(s) %.3f ms, "
                + "1/%d rate (%.1f TPS), near [%d, %d], last %s",
            region.id(), region.sectionCount(),
            entities, region.lastTickNanos() / 1_000_000.0,
            blockEntities, region.lastBlockEntityNanos() / 1_000_000.0,
            region.tickDivisor(), 20.0 / region.tickDivisor(),
            blockX, blockZ, region.lastThreadName())), false);
    }

    private static int phases(CommandSourceStack source) {
        int activePhases = sendPhaseStats(source) + sendBoundaryStats(source);

        int callbackPending = 0;
        int callbackPeak = 0;
        int blockEventPending = 0;
        int blockEventPeak = 0;
        for (LevelRegionIndex index : RegionTracker.indexes()) {
            callbackPending += index.deferredCallbacks().pendingCount();
            callbackPeak += index.deferredCallbacks().peakPendingCount();
            blockEventPending += index.blockEvents().pendingCount();
            blockEventPeak += index.blockEvents().peakPendingCount();
        }
        int pending = callbackPending
            + org.texboobcat.optimal.region.DeferredMainThreadWork.pendingCount();
        int peak = callbackPeak
            + org.texboobcat.optimal.region.DeferredMainThreadWork.peakPendingCount();
        source.sendSuccess(() -> Component.literal(String.format(
            "deferred queues: %d pending, peak %d; parallel=%s, unavailable chunks=%d",
            pending, peak, RegionTracker.parallelAllowed(), RegionTracker.unavailableChunks())),
            false);
        final int eventPending = blockEventPending;
        final int eventPeak = blockEventPeak;
        source.sendSuccess(() -> Component.literal(String.format(
            "block-event queues: %d pending, peak %d", eventPending, eventPeak)), false);
        org.texboobcat.optimal.region.ParallelNaturalSpawner.Stats spawning =
            org.texboobcat.optimal.region.ParallelNaturalSpawner.stats();
        source.sendSuccess(() -> Component.literal(String.format(
            "spawn reservations: %d committed + %d rolled back / %d reserved, "
                + "%d rejected, %d pending (peak %d), cap violations %d, failures %d; %s",
            spawning.committed(), spawning.rolledBack(), spawning.reserved(),
            spawning.rejected(), spawning.outstanding(), spawning.peakOutstanding(),
            spawning.capViolations(), spawning.failures(),
            org.texboobcat.optimal.region.ParallelNaturalSpawner.parallelAllowed()
                ? "parallel"
                : "serial fallback: "
                    + org.texboobcat.optimal.region.ParallelNaturalSpawner.degradeReason())),
            false);
        return activePhases;
    }

    private static int sendPhaseStats(CommandSourceStack source) {
        int active = 0;
        for (PhaseStats.Snapshot phase : PhaseStats.snapshots()) {
            if (phase.workerCalls() == 0 && phase.mainCalls() == 0 && phase.failures() == 0) {
                continue;
            }
            active++;
            source.sendSuccess(() -> Component.literal(String.format(
                "%s: worker %d/%.3f ms, main %d/%.3f ms, peak %d, active %d, "
                    + "wait %.3f ms, failures %d",
                phase.phase().label(), phase.workerCalls(), phase.workerNanos() / 1_000_000.0,
                phase.mainCalls(), phase.mainNanos() / 1_000_000.0,
                phase.maxConcurrent(), phase.active(), phase.waitNanos() / 1_000_000.0,
                phase.failures())), false);
        }
        return active;
    }

    private static int sendBoundaryStats(CommandSourceStack source) {
        int active = 0;
        for (MainThreadBoundaries.Snapshot boundary : MainThreadBoundaries.snapshots()) {
            if (boundary.queued() == 0 && boundary.directCalls() == 0
                && boundary.queuedFailures() == 0 && boundary.directFailures() == 0) {
                continue;
            }
            active++;
            source.sendSuccess(() -> Component.literal(String.format(
                "boundary %s: queued %d, replayed %d, direct %d, pending %d, "
                    + "main %.3f ms, failures %d/%d, balanced=%s, last %s",
                boundary.boundary().label(), boundary.queued(), boundary.replayed(),
                boundary.directCalls(), boundary.pending(),
                boundary.mainNanos() / 1_000_000.0, boundary.queuedFailures(),
                boundary.directFailures(), boundary.balanced(), boundary.lastSource())), false);
        }
        return active;
    }

    private static int violations(CommandSourceStack source) {
        ViolationLog log = RegionTracker.violations();
        long total = log.total();
        if (total == 0) {
            source.sendSuccess(() -> Component.literal("optimal: no ownership violations recorded"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal(String.format(
            "optimal: %d violation(s) across %d distinct site(s)", total, log.distinctSites())), false);

        int shown = 0;
        for (ViolationLog.Entry entry : log.entries()) {
            if (shown++ >= MAX_VIOLATION_SITES_SHOWN) {
                break;
            }
            source.sendSuccess(() -> Component.literal(String.format(
                "  x%d region#%d %s %s [%d, %d] at %s",
                entry.count(), entry.regionId(), entry.mode(), entry.levelKey(),
                entry.chunkX(), entry.chunkZ(), entry.site())), false);
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    private static int clearViolations(CommandSourceStack source) {
        RegionTracker.violations().clear();
        source.sendSuccess(() -> Component.literal("optimal: violation log cleared"), false);
        return 1;
    }

    private static int toggleVisualization(CommandSourceStack source)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return setVisualization(source, !VISUALIZERS.contains(player.getUUID()));
    }

    private static int setVisualization(CommandSourceStack source, boolean enabled)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (enabled) {
            VISUALIZERS.add(player.getUUID());
        } else {
            VISUALIZERS.remove(player.getUUID());
            player.displayClientMessage(Component.empty(), true);
            if (player.connection.hasChannel(RegionVisualizationPayload.TYPE.id())) {
                PacketDistributor.sendToPlayer(player, RegionVisualizationPayload.disabled());
            }
        }
        String backend = enabled
            ? player.connection.hasChannel(RegionVisualizationPayload.TYPE.id())
                ? " (GPU overlay)" : " (particle fallback)"
            : "";
        source.sendSuccess(() -> Component.literal(
            "optimal: region visualization " + (enabled ? "enabled" : "disabled") + backend), false);
        return 1;
    }

    // Modded clients get the GPU overlay; unmodified clients retain native particles.
    public static void tickVisualization(ServerLevel level) {
        if (VISUALIZERS.isEmpty() || level.getGameTime() % 10 != 0) {
            return;
        }
        LevelRegionIndex index = RegionTracker.index(level);
        if (index == null) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (VISUALIZERS.contains(player.getUUID())) {
                visualize(index, player);
            }
        }
    }

    private static void visualize(LevelRegionIndex index, ServerPlayer player) {
        ChunkPos chunk = player.chunkPosition();
        Region region = index.regionForChunk(chunk);
        boolean gpuOverlay = player.connection.hasChannel(RegionVisualizationPayload.TYPE.id());
        if (gpuOverlay) {
            PacketDistributor.sendToPlayer(player, visualizationSnapshot(index, chunk, region));
        }
        if (region == null) {
            player.displayClientMessage(Component.literal(String.format(
                "Optimal | chunk [%d, %d] | no active region (main thread)", chunk.x, chunk.z)),
                true);
            return;
        }

        int shift = index.regionizer().sectionShift();
        long cell = RegionSectionPos.fromChunk(chunk.x, chunk.z, shift);
        int cellSize = 1 << (shift + 4);
        int cellX = RegionSectionPos.x(cell) * cellSize;
        int cellZ = RegionSectionPos.z(cell) * cellSize;
        double y = Math.floor(player.getY()) + 0.05;
        if (!gpuOverlay) {
            DustParticleOptions regionParticle = region.lastWorkerIndex() < 0
                ? IDLE_PARTICLE
                : WORKER_PARTICLES[Math.floorMod(region.lastWorkerIndex(), WORKER_PARTICLES.length)];
            drawSquare(player.serverLevel(), player, regionParticle, cellX, cellZ, cellSize,
                Math.max(4, cellSize / 32), y);
            drawSquare(player.serverLevel(), player, CHUNK_PARTICLE,
                chunk.getMinBlockX(), chunk.getMinBlockZ(), 16, 2, y);
        }
        player.displayClientMessage(Component.literal(String.format(
            "Optimal | region #%d | last worker T%d (pooled) | chunk [%d, %d] | rate 1/%d",
            region.id(), region.lastWorkerIndex(), chunk.x, chunk.z, region.tickDivisor())), true);
    }

    private static RegionVisualizationPayload visualizationSnapshot(LevelRegionIndex index,
                                                                     ChunkPos playerChunk,
                                                                     Region currentRegion) {
        List<RegionVisualizationPayload.Cell> cells = new ArrayList<>();
        for (Region region : index.regionizer().regions()) {
            int worker = region.lastWorkerIndex();
            int divisor = region.tickDivisor();
            float millis = (region.lastTickNanos() + region.lastBlockEntityNanos()) / 1_000_000.0F;
            for (it.unimi.dsi.fastutil.longs.LongIterator iterator = region.sections().iterator();
                 iterator.hasNext(); ) {
                cells.add(new RegionVisualizationPayload.Cell(iterator.nextLong(), region.id(),
                    worker, divisor, millis));
            }
        }
        long playerCell = RegionSectionPos.fromChunk(playerChunk.x, playerChunk.z,
            index.regionizer().sectionShift());
        int playerCellX = RegionSectionPos.x(playerCell);
        int playerCellZ = RegionSectionPos.z(playerCell);
        cells.sort(Comparator.comparingLong(cell -> {
            long dx = (long) RegionSectionPos.x(cell.section()) - playerCellX;
            long dz = (long) RegionSectionPos.z(cell.section()) - playerCellZ;
            return dx * dx + dz * dz;
        }));
        if (cells.size() > RegionVisualizationPayload.MAX_CELLS) {
            cells = new ArrayList<>(cells.subList(0, RegionVisualizationPayload.MAX_CELLS));
        }
        return new RegionVisualizationPayload(true, index.regionizer().sectionShift(),
            currentRegion == null ? -1 : currentRegion.id(), index.lastParallelRegions(),
            index.regionizer().mergeCount(), index.regionizer().splitCount(), cells);
    }

    private static void drawSquare(ServerLevel level, ServerPlayer player,
                                   DustParticleOptions particle, int minX, int minZ,
                                   int size, int step, double y) {
        for (int offset = 0; offset <= size; offset += step) {
            sendParticle(level, player, particle, minX + offset, y, minZ);
            sendParticle(level, player, particle, minX + offset, y, minZ + size);
            sendParticle(level, player, particle, minX, y, minZ + offset);
            sendParticle(level, player, particle, minX + size, y, minZ + offset);
        }
    }

    private static void sendParticle(ServerLevel level, ServerPlayer player,
                                     DustParticleOptions particle, double x, double y, double z) {
        level.sendParticles(player, particle, true, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
    }

    private static DustParticleOptions dust(float red, float green, float blue) {
        return new DustParticleOptions(new Vector3f(red, green, blue), 1.0F);
    }

}

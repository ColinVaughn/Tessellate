package org.texboobcat.tessellate.command;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.texboobcat.tessellate.Config;
import org.texboobcat.tessellate.region.LevelRegionIndex;
import org.texboobcat.tessellate.region.LoadBalancePlanner;
import org.texboobcat.tessellate.region.MainThreadBoundaries;
import org.texboobcat.tessellate.region.PhaseStats;
import org.texboobcat.tessellate.region.Region;
import org.texboobcat.tessellate.region.RegionLoad;
import org.texboobcat.tessellate.region.RegionSectionPos;
import org.texboobcat.tessellate.region.RegionTracker;
import org.texboobcat.tessellate.region.RegionWorkers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Readable output for the tessellate commands: a verdict first, then colored rows with load bars,
// in the spirit of spark and /neoforge tps. Thresholds are against the 50 ms tick budget.
final class TessellateReport {

    private static final double BUDGET_MS = 50.0;
    private static final double WARN_MS = 10.0;
    private static final double BAD_MS = 25.0;
    private static final int BAR = 12;
    private static final int DEFAULT_ROWS = 10;
    private static final String SEP = " | ";

    private TessellateReport() {
    }

    // ---- building blocks ---------------------------------------------------------------------

    private static MutableComponent text(String value, ChatFormatting... formats) {
        return Component.literal(value).withStyle(formats);
    }

    private static ChatFormatting level(double ms) {
        if (ms >= BAD_MS) {
            return ChatFormatting.RED;
        }
        return ms >= WARN_MS ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
    }

    private static MutableComponent bar(double ms) {
        int filled = (int) Math.max(0, Math.min(BAR, Math.round(ms / BUDGET_MS * BAR)));
        return text("█".repeat(filled), level(ms)).append(text("░".repeat(BAR - filled), ChatFormatting.DARK_GRAY));
    }

    private static MutableComponent header(String title) {
        return text("Tessellate ", ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(text("» ", ChatFormatting.DARK_GRAY))
            .append(text(title, ChatFormatting.GRAY));
    }

    private static MutableComponent label(String name, MutableComponent value) {
        return text(name + " ", ChatFormatting.DARK_AQUA).append(value);
    }

    private static MutableComponent sep() {
        return text(SEP, ChatFormatting.DARK_GRAY);
    }

    private static String ms(double nanos) {
        return String.format("%.1f ms", nanos / 1_000_000.0);
    }

    private static void send(CommandSourceStack source, MutableComponent line) {
        source.sendSuccess(() -> line, false);
    }

    private static double costMs(Region region) {
        RegionLoad load = region.load();
        long nanos = load.samples() > 0 ? load.cost() : region.lastTickNanos() + region.lastBlockEntityNanos();
        return nanos / 1_000_000.0;
    }

    private static String dominant(RegionLoad load) {
        double entities = load.average(RegionLoad.Metric.ENTITY);
        double blockEntities = load.average(RegionLoad.Metric.BLOCK_ENTITY);
        double scheduled = load.average(RegionLoad.Metric.SCHEDULED);
        double sum = entities + blockEntities + scheduled;
        if (sum <= 0.0) {
            return "no data yet";
        }
        String name = "entities";
        double top = entities;
        if (blockEntities > top) {
            name = "block entities";
            top = blockEntities;
        }
        if (scheduled > top) {
            name = "scheduled ticks";
            top = scheduled;
        }
        return name + " " + Math.round(100.0 * top / sum) + "%";
    }

    private static List<Region> allRegions() {
        List<Region> regions = new ArrayList<>();
        for (LevelRegionIndex index : RegionTracker.indexes()) {
            regions.addAll(index.regionizer().regions());
        }
        regions.sort(Comparator.comparingDouble(TessellateReport::costMs).reversed());
        return regions;
    }

    private static long phaseFailures() {
        long failures = 0;
        for (PhaseStats.Snapshot phase : PhaseStats.snapshots()) {
            failures += phase.failures();
        }
        return failures;
    }

    // ---- status ------------------------------------------------------------------------------

    static int status(CommandSourceStack source) {
        send(source, header("status"));
        send(source, verdict());
        String mode = !Config.parallelTicking ? "serial"
            : Config.asyncRegionLoops ? "async" : "staged (barrier)";
        send(source, label("Mode", text(mode, ChatFormatting.WHITE))
            .append(sep()).append(label("workers", text(String.valueOf(RegionWorkers.poolSize()), ChatFormatting.WHITE)))
            .append(sep()).append(label("balancing", flag(RegionWorkers.balancing()))));
        int regions = 0;
        int chunks = 0;
        for (LevelRegionIndex index : RegionTracker.indexes()) {
            regions += index.regionizer().regionCount();
            chunks += index.tickingChunkCount();
        }
        send(source, label("Regions", text(String.valueOf(regions), ChatFormatting.WHITE))
            .append(sep()).append(label("ticking chunks", text(String.valueOf(chunks), ChatFormatting.WHITE)))
            .append(sep()).append(label("server tick", text(ms(RegionTracker.serverTickNanos()),
                level(RegionTracker.serverTickNanos() / 1_000_000.0)))));
        long violations = RegionTracker.violations().total();
        long failures = phaseFailures();
        send(source, label("Safety", text(violations + " violation(s)",
                violations == 0 ? ChatFormatting.GREEN : ChatFormatting.RED))
            .append(sep()).append(text(failures + " failure(s)",
                failures == 0 ? ChatFormatting.GREEN : ChatFormatting.RED)));
        return regions;
    }

    private static MutableComponent flag(boolean on) {
        return on ? text("ON", ChatFormatting.GREEN) : text("OFF", ChatFormatting.RED);
    }

    private static MutableComponent verdict() {
        if (!Config.regionsEnabled) {
            return text("✘ Region tracking is disabled", ChatFormatting.RED);
        }
        if (!RegionTracker.parallelAllowed()) {
            return text("✘ DEGRADED to serial: " + RegionTracker.degradeReason(), ChatFormatting.RED);
        }
        if (!RegionWorkers.isRunning()) {
            return text("! Region workers are not running", ChatFormatting.YELLOW);
        }
        if (RegionTracker.violations().total() > 0) {
            return text("✘ " + RegionTracker.violations().total() + " ownership violation(s): /tessellate violations",
                ChatFormatting.RED);
        }
        List<Region> regions = allRegions();
        if (regions.isEmpty()) {
            return text("No active regions", ChatFormatting.GRAY);
        }
        Region worst = regions.get(0);
        double ms = costMs(worst);
        String detail = "region #" + worst.id() + ": " + String.format("%.1f ms", ms)
            + " (" + dominant(worst.load()) + ")";
        if (ms >= BAD_MS) {
            return text("✘ Bottleneck: " + detail, ChatFormatting.RED);
        }
        if (ms >= WARN_MS) {
            return text("! Heaviest " + detail, ChatFormatting.YELLOW);
        }
        return text("✔ Healthy. Heaviest " + detail, ChatFormatting.GREEN);
    }

    // ---- regions -----------------------------------------------------------------------------

    static int regions(CommandSourceStack source, boolean all) {
        send(source, header("regions"));
        int total = 0;
        for (LevelRegionIndex index : RegionTracker.indexes()) {
            List<Region> regions = new ArrayList<>(index.regionizer().regions());
            regions.sort(Comparator.comparingDouble(TessellateReport::costMs).reversed());
            send(source, text(index.levelKey(), ChatFormatting.AQUA)
                .append(text(" - " + regions.size() + " region(s), " + index.tickingChunkCount()
                    + " ticking chunk(s), merges " + index.regionizer().mergeCount()
                    + ", splits " + index.regionizer().splitCount(), ChatFormatting.GRAY)));
            int shown = 0;
            for (Region region : regions) {
                if (!all && shown++ >= DEFAULT_ROWS) {
                    send(source, text("  ... " + (regions.size() - DEFAULT_ROWS)
                        + " more (/tessellate regions all)", ChatFormatting.DARK_GRAY));
                    break;
                }
                send(source, regionRow(source, index, region));
            }
            total += regions.size();
        }
        if (total == 0) {
            send(source, text("No active regions", ChatFormatting.GRAY));
        }
        return total;
    }

    private static MutableComponent regionRow(CommandSourceStack source, LevelRegionIndex index, Region region) {
        RegionLoad load = region.load();
        double ms = costMs(region);
        long anySection = region.sections().iterator().nextLong();
        int shift = index.regionizer().sectionShift();
        int blockX = RegionSectionPos.x(anySection) << (shift + 4);
        int blockZ = RegionSectionPos.z(anySection) << (shift + 4);

        MutableComponent hover = text("Region #" + region.id() + "\n", ChatFormatting.GOLD)
            .append(text("entities " + ms(load.average(RegionLoad.Metric.ENTITY)) + "\n", ChatFormatting.GRAY))
            .append(text("block entities " + ms(load.average(RegionLoad.Metric.BLOCK_ENTITY)) + "\n", ChatFormatting.GRAY))
            .append(text("scheduled " + ms(load.average(RegionLoad.Metric.SCHEDULED)) + "\n", ChatFormatting.GRAY))
            .append(text("queue " + Math.round(load.average(RegionLoad.Metric.QUEUE)) + " msg\n", ChatFormatting.GRAY))
            .append(text("CPU " + Math.round(100.0 * load.average(RegionLoad.Metric.CPU)) + "% of a tick\n", ChatFormatting.GRAY))
            .append(text(region.sectionCount() + " section(s), near [" + blockX + ", " + blockZ + "]\n", ChatFormatting.GRAY))
            .append(text("last thread " + region.lastThreadName(), ChatFormatting.GRAY));
        MutableComponent row = text("#" + region.id() + " ", ChatFormatting.WHITE)
            .append(bar(ms))
            .append(text(" " + String.format("%.1f ms", ms), level(ms)))
            .append(text(" " + dominant(load), ChatFormatting.GRAY))
            .append(sep())
            .append(text(load.entityCount() + " ent, " + load.blockEntityCount() + " BE", ChatFormatting.WHITE))
            .append(sep())
            .append(text("T" + region.lastWorkerIndex(), ChatFormatting.DARK_AQUA));
        if (region.tickDivisor() > 1) {
            row.append(text(" throttled 1/" + region.tickDivisor(), ChatFormatting.YELLOW));
        }
        boolean sameLevel = index.levelKey().equals(source.getLevel().dimension().location().toString());
        return row.withStyle(style -> {
            style = style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));
            return sameLevel
                ? style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                    "/tp @s " + blockX + " ~ " + blockZ))
                : style;
        });
    }

    // ---- phases ------------------------------------------------------------------------------

    static int phases(CommandSourceStack source) {
        send(source, header("phases"));
        int active = 0;
        for (PhaseStats.Snapshot phase : PhaseStats.snapshots()) {
            if (phase.workerCalls() == 0 && phase.mainCalls() == 0 && phase.failures() == 0) {
                continue;
            }
            active++;
            send(source, phaseRow(phase));
        }
        for (MainThreadBoundaries.Snapshot boundary : MainThreadBoundaries.snapshots()) {
            if (boundary.queued() != 0 || boundary.directCalls() != 0) {
                send(source, boundaryRow(boundary));
            }
        }
        return active;
    }

    private static MutableComponent boundaryRow(MainThreadBoundaries.Snapshot boundary) {
        boolean bad = !boundary.balanced() || boundary.queuedFailures() + boundary.directFailures() > 0;
        return text("» " + boundary.boundary().label(), ChatFormatting.DARK_AQUA)
            .append(text(" queued " + boundary.queued() + ", replayed " + boundary.replayed()
                + ", direct " + boundary.directCalls() + ", pending " + boundary.pending(),
                ChatFormatting.GRAY))
            .append(text(bad ? " ✘" : " ✔", bad ? ChatFormatting.RED : ChatFormatting.GREEN));
    }

    private static MutableComponent phaseRow(PhaseStats.Snapshot phase) {
        double workerAvg = phase.workerCalls() == 0 ? 0.0 : phase.workerNanos() / 1_000_000.0 / phase.workerCalls();
        double mainAvg = phase.mainCalls() == 0 ? 0.0 : phase.mainNanos() / 1_000_000.0 / phase.mainCalls();
        MutableComponent row = text(phase.phase().label() + " ", ChatFormatting.WHITE)
            .append(text(String.format("workers %.2f ms/call x%d", workerAvg, phase.workerCalls()),
                phase.workerCalls() == 0 ? ChatFormatting.DARK_GRAY : level(workerAvg * 10)));
        row.append(sep()).append(text(String.format("main %.2f ms/call x%d", mainAvg, phase.mainCalls()),
            phase.mainCalls() == 0 ? ChatFormatting.DARK_GRAY : level(mainAvg * 10)));
        row.append(sep()).append(text("peak " + phase.maxConcurrent(), ChatFormatting.GRAY));
        if (phase.failures() > 0) {
            row.append(text(" " + phase.failures() + " FAILED", ChatFormatting.RED));
        }
        return row;
    }

    // ---- balance and workers -----------------------------------------------------------------

    static int balance(CommandSourceStack source) {
        send(source, header("balance"));
        long changes = 0;
        long repacks = 0;
        for (LevelRegionIndex index : RegionTracker.indexes()) {
            changes += index.planner().classChanges();
            repacks += index.planner().repacks();
        }
        send(source, label("Balancing", flag(RegionWorkers.balancing()))
            .append(sep()).append(text("class changes " + changes + ", repacks " + repacks, ChatFormatting.GRAY)));
        LoadBalancePlanner.Plan plan = RegionWorkers.lastPlan();
        if (plan == null) {
            send(source, text("No plan yet (needs 2+ regions ticking)", ChatFormatting.GRAY));
            return 0;
        }
        ChatFormatting tone = plan.imbalance() > 1.5 ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
        send(source, label("Last plan", text(plan.groups().size() + " task(s)", ChatFormatting.WHITE))
            .append(sep()).append(label("imbalance", text(String.format("%.2fx", plan.imbalance()), tone))));
        for (LoadBalancePlanner.Group group : plan.groups()) {
            double groupMs = group.costNanos() / 1_000_000.0;
            send(source, text(group.heavy() ? "heavy " : "light ", group.heavy() ? ChatFormatting.RED : ChatFormatting.GREEN)
                .append(bar(groupMs))
                .append(text(String.format(" %.1f ms ", groupMs), level(groupMs)))
                .append(text("regions " + group.regionIds(), ChatFormatting.GRAY)));
        }
        return plan.groups().size();
    }

    static int workers(CommandSourceStack source) {
        send(source, header("workers"));
        send(source, label("Pool", text(RegionWorkers.poolSize() + " thread(s)", ChatFormatting.WHITE))
            .append(sep()).append(label("host cores",
                text(String.valueOf(Runtime.getRuntime().availableProcessors()), ChatFormatting.WHITE)))
            .append(sep()).append(label("in flight", text(String.valueOf(RegionWorkers.activeTasks()), ChatFormatting.WHITE))));
        if (RegionWorkers.scheduler() != null) {
            send(source, label("Steals", text(String.valueOf(RegionWorkers.scheduler().stealCount()), ChatFormatting.WHITE)));
        }
        send(source, text("/tessellate workers <N|all|auto>", ChatFormatting.DARK_GRAY));
        return RegionWorkers.poolSize();
    }

    static int setWorkers(CommandSourceStack source, int threads) {
        RegionWorkers.resize(threads);
        send(source, header("workers").append(text(" -> " + RegionWorkers.poolSize() + " thread(s)", ChatFormatting.GREEN)));
        return RegionWorkers.poolSize();
    }

    static int setBalancing(CommandSourceStack source, boolean on) {
        RegionWorkers.setBalancing(on);
        send(source, header("balance").append(text(" -> ", ChatFormatting.GRAY)).append(flag(on)));
        return 1;
    }
}

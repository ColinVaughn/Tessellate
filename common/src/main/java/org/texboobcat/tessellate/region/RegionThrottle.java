package org.texboobcat.tessellate.region;

import org.texboobcat.tessellate.Config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

// Decides how often each region is allowed to tick.
//
// This is what delivers the project's goal. Ticking every region every tick means one
// overloaded region pushes the whole server tick past 50 ms, and every player pays for it. This
// is the 6000-mob benchmark, where an empty area 1024 blocks from the lag machine still ran at
// 11.3 TPS. Ticking an overloaded region less often keeps the server's total per-tick
// work inside its budget, so the server holds 20 TPS and only the overloaded region runs slowly.
//
// The algorithm is deliberately simple, because a throttle that is hard to predict is worse
// than one that is slightly subtessellate:
//
// - Every region starts at divisor 1 and ticks every tick.
// - While the projected per-tick cost exceeds the budget, double the divisor of the region
// contributing the most, up to maxDivisor.
//
// Projected cost of a region is cost / divisor: a region ticking every fourth tick
// contributes a quarter of its cost to the average tick.
//
// What those contributions add up to depends on how regions execute. Serially the tick costs
// their sum. In parallel it costs the wall-clock makespan instead, which is bounded below
// by the largest single region, which cannot be split across threads, and by the total
// divided across the available lanes. Using the serial sum while regions run in parallel would
// throttle regions that comfortably fit, and would hide the entire benefit of running them in
// parallel.
//
// regionBudgetMillis is now a floor rather than a target: regions are guaranteed at
// least that much of a tick however large the rest of the server's overhead grows. What decides
// when the throttle engages is targetTickMillis; see effectiveBudgetNanos.
//
// Regions below minThrottleMillis are never throttled. Without that floor, rounding and
// measurement noise would throttle ordinary regions that are not the problem, and players would
// see stutter that the server did not need to introduce.
public final class RegionThrottle {

    private RegionThrottle() {
    }

    // Assigns a divisor to every region. Main thread, once per level tick, before any subsystem
    // runs so that all three see the same decision.
    //
    // Reads configuration and delegates. The policy itself takes its inputs as arguments so it
    // can be tested without a running game: Config builds a ModConfigSpec in its
    // static initializer and cannot even be loaded outside NeoForge.
    public static void apply(Collection<Region> regions) {
        apply(regions,
            Config.adaptiveThrottling,
            effectiveBudgetNanos(
                RegionTracker.serverTickNanos(),
                RegionTracker.regionWorkNanos(),
                (long) (Config.targetTickMillis * 1_000_000.0),
                (long) (Config.regionBudgetMillis * 1_000_000.0)),
            (long) (Config.minThrottleMillis * 1_000_000.0),
            Config.maxTickDivisor,
            parallelLanes(regions.size()));
    }

    // How much of a tick region work may take.
    //
    // Derived from what the server is actually doing rather than fixed, because a fixed budget
    // throttles by the wrong criterion. With a 25 ms budget the throttle engaged whenever region
    // work passed 25 ms, even on a server holding a comfortable 20 TPS at 41 ms per tick, where it
    // halved a busy area's tick rate and bought nothing. Measured against no mod at all, that was a
    // regression rather than an improvement.
    //
    // What matters is the tick as a whole. The chunk system, networking, and block entities
    // outside regions are overhead the regions cannot control,
    // so the budget is whatever is left of the target after paying it. On a healthy server the
    // remainder is large and nothing is throttled; as overhead or region cost grows, the budget
    // tightens and the throttle engages when the tick is at risk.
    //
    // serverTickNanos: measured tick time, smoothed by the server
    // regionWorkNanos: what regions cost inside that tick
    // targetNanos: the tick time to steer toward, under the 50 ms limit
    // floorNanos: regions are never squeezed below this, however large the overhead
    static long effectiveBudgetNanos(long serverTickNanos, long regionWorkNanos, long targetNanos,
                                     long floorNanos) {
        // Before any tick has been measured, fall back to the floor rather than inventing headroom.
        if (serverTickNanos <= 0L) {
            return floorNanos;
        }
        long overhead = Math.max(0L, serverTickNanos - regionWorkNanos);
        return Math.max(floorNanos, targetNanos - overhead);
    }

    // The policy, with every input explicit.
    static void apply(Collection<Region> regions, boolean adaptive, long budgetNanos,
                      long floorNanos, int maxDivisor, int lanes) {
        if (!adaptive) {
            for (Region region : regions) {
                region.setTickDivisor(1);
            }
            return;
        }

        List<Region> all = new ArrayList<>(regions);
        List<Region> candidates = resetAndFindCandidates(regions, floorNanos);

        long projected = projectedTickNanos(all, lanes);
        if (projected <= budgetNanos || candidates.isEmpty()) {
            return;
        }

        // Most expensive contributor first; re-sorted each pass because doubling changes the order.
        candidates.sort(Comparator.comparingLong(RegionThrottle::contribution).reversed());
        throttle(all, candidates, projected, budgetNanos, maxDivisor, lanes);
    }

    private static List<Region> resetAndFindCandidates(Collection<Region> regions,
                                                         long floorNanos) {
        List<Region> candidates = new ArrayList<>(regions.size());
        for (Region region : regions) {
            region.setTickDivisor(1);
            if (region.costNanos() > floorNanos) {
                candidates.add(region);
            }
        }
        return candidates;
    }

    private static void throttle(List<Region> all, List<Region> candidates, long projected,
                                 long budgetNanos, int maxDivisor, int lanes) {
        while (projected > budgetNanos && !candidates.isEmpty()) {
            Region worst = candidates.get(0);
            int divisor = worst.tickDivisor();
            if (divisor >= maxDivisor) {
                candidates.remove(0);
                continue;
            }

            worst.setTickDivisor(Math.min(maxDivisor, divisor * 2));
            // Recomputed rather than adjusted: under a makespan estimate, halving one region's
            // cost does not reduce the projection by that amount when a different region is now
            // the tallest lane.
            projected = projectedTickNanos(all, lanes);

            candidates.sort(Comparator.comparingLong(RegionThrottle::contribution).reversed());
        }
    }

    // What this region adds to the cost of an average tick at its current divisor.
    private static long contribution(Region region) {
        return region.costNanos() / Math.max(1, region.tickDivisor());
    }

    // How long an average tick's region work will take.
    //
    // Serially that is the sum. In parallel it is the makespan, estimated as the larger of the
    // biggest single region and the total spread evenly over the lanes. These are the standard lower
    // bounds, and close enough for a control loop that only has to pick a power-of-two divisor.
    static long projectedTickNanos(List<Region> regions, int lanes) {
        long sum = 0;
        long largest = 0;
        for (Region region : regions) {
            long contribution = contribution(region);
            sum += contribution;
            largest = Math.max(largest, contribution);
        }
        return lanes <= 1 ? sum : Math.max(largest, sum / lanes);
    }

    // Lanes actually available to region work.
    //
    // Never more than the number of regions: eight idle workers do not make one region cheaper.
    static int parallelLanes(int regionCount) {
        if (!Config.parallelTickingConfigured()
            || !RegionTracker.parallelAllowed()
            || !RegionWorkers.isRunning()) {
            return 1;
        }
        return Math.max(1, Math.min(RegionWorkers.poolSize(), regionCount));
    }
}

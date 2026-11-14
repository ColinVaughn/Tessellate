package org.texboobcat.tessellate.region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides how region work is grouped into scheduler tasks each tick.
 *
 * <p>This is deliberately a scheduling decision and not a topology one. Regions are kept apart by
 * a gap so that no two of them ever touch the same chunks; that gap is what makes ticking them
 * concurrently safe. Cutting a connected region along a load boundary would put two regions side
 * by side and break that guarantee, so a hot region stays one region (and one task). What can be
 * changed freely is which regions share a task:
 *
 * <ul>
 *   <li>a <b>heavy</b> region gets a task to itself, so the largest tasks start first and a long
 *       tail cannot hide behind small ones;</li>
 *   <li>many <b>light</b> regions are coalesced into a few tasks, since a task that costs less
 *       than the scheduler's hand-off overhead is wasted parallelism. This is the "merge small
 *       neighbours" half, done where it cannot affect correctness.</li>
 * </ul>
 *
 * <p>To avoid shuffling the plan every tick, a region only changes class after its cost has stayed
 * past a threshold band for several consecutive plans, light groups are sticky, and a full repack
 * happens only when the plan is clearly unbalanced and a cooldown has passed. The plan is a pure
 * function of the inputs and previous state: no clocks, no randomness.
 *
 * <p>Not thread-safe; call from the main thread only.
 */
public final class LoadBalancePlanner {

    /** Tunables. Defaults are conservative: slow to change class, fast enough to follow real load. */
    public record Settings(
        long minTaskNanos,        // a task cheaper than this is not worth a scheduler hand-off
        double tasksPerLane,      // aim for this many tasks per worker so stragglers can be balanced
        double band,              // hysteresis half-width around the heavy/light threshold
        int confirmTicks,         // consecutive plans beyond the band before a region changes class
        double repackImbalance,   // repack light groups when makespan / ideal exceeds this ...
        int repackCooldownTicks   // ... but never more often than this
    ) {
        public static final Settings DEFAULT = new Settings(250_000L, 3.0, 0.25, 20, 1.5, 100);

        public Settings {
            if (minTaskNanos < 0 || tasksPerLane < 1.0 || band < 0.0 || band >= 1.0
                || confirmTicks < 1 || repackImbalance < 1.0 || repackCooldownTicks < 0) {
                throw new IllegalArgumentException("invalid balance settings");
            }
        }
    }

    /** One region's input to a plan. */
    public record Item(int regionId, long costNanos, int priority) {
        public Item(int regionId, long costNanos) {
            this(regionId, costNanos, 0);
        }
    }

    /** Regions that run back to back inside a single scheduler task. */
    public record Group(List<Integer> regionIds, long costNanos, int priority, boolean heavy) {
    }

    /** The result of one planning pass; groups are ordered so the most important and costly start first. */
    public record Plan(List<Group> groups, boolean repacked, double imbalance) {
    }

    private static final class RegionState {
        boolean heavy;
        int streak;          // consecutive plans the region has sat on the other side of the band
        int lightGroup = -1; // sticky light group id, or -1
    }

    private final Settings settings;
    private final Map<Integer, RegionState> states = new HashMap<>();
    private int nextGroupId;
    private long plans;
    private long lastRepackPlan = Long.MIN_VALUE / 2;
    private long classChanges;
    private long repacks;

    public LoadBalancePlanner() {
        this(Settings.DEFAULT);
    }

    public LoadBalancePlanner(Settings settings) {
        this.settings = settings;
    }

    public long classChanges() {
        return this.classChanges;
    }

    public long repacks() {
        return this.repacks;
    }

    public Plan plan(List<Item> items, int lanes) {
        this.plans++;
        int workers = Math.max(1, lanes);
        long total = 0L;
        for (Item item : items) {
            total += Math.max(0L, item.costNanos());
        }
        forgetMissing(items);

        long target = Math.max(this.settings.minTaskNanos(),
            Math.round(total / (workers * this.settings.tasksPerLane())));

        List<Item> heavy = new ArrayList<>();
        List<Item> light = new ArrayList<>();
        for (Item item : items) {
            RegionState state = this.states.computeIfAbsent(item.regionId(), id -> new RegionState());
            classify(state, item.costNanos(), target);
            (state.heavy ? heavy : light).add(item);
        }

        List<Group> groups = new ArrayList<>(items.size());
        for (Item item : heavy) {
            groups.add(new Group(List.of(item.regionId()), item.costNanos(), item.priority(), true));
        }

        boolean repacked = packLight(light, target, workers, groups);

        groups.sort(Comparator.comparingInt(Group::priority).reversed()
            .thenComparing(Comparator.comparingLong(Group::costNanos).reversed())
            .thenComparingInt(g -> g.regionIds().get(0)));
        return new Plan(List.copyOf(groups), repacked, imbalance(groups, total, workers));
    }

    // A region is heavy once it has stayed above target*(1+band) for confirmTicks plans, and light
    // again once it has stayed below target*(1-band) for as long. Inside the band nothing changes,
    // and any return to the near side resets the streak, so noise around the threshold is ignored.
    private void classify(RegionState state, long cost, long target) {
        double upper = target * (1.0 + this.settings.band());
        double lower = target * (1.0 - this.settings.band());
        boolean wantsFlip = state.heavy ? cost <= lower : cost >= upper;
        if (!wantsFlip) {
            state.streak = 0;
            return;
        }
        if (++state.streak >= this.settings.confirmTicks()) {
            state.heavy = !state.heavy;
            state.streak = 0;
            state.lightGroup = -1;
            this.classChanges++;
        }
    }

    private boolean packLight(List<Item> light, long target, int workers, List<Group> out) {
        if (light.isEmpty()) {
            return false;
        }
        long capacity = Math.round(target * (1.0 + this.settings.band()));
        Map<Integer, List<Item>> sticky = new HashMap<>();
        List<Item> loose = new ArrayList<>();
        for (Item item : light) {
            int group = this.states.get(item.regionId()).lightGroup;
            if (group >= 0) {
                sticky.computeIfAbsent(group, id -> new ArrayList<>()).add(item);
            } else {
                loose.add(item);
            }
        }

        // Keep each previous group that still fits; whatever overflows is repacked below.
        Map<Integer, Long> load = new HashMap<>();
        for (Map.Entry<Integer, List<Item>> entry : sticky.entrySet()) {
            entry.getValue().sort(Comparator.comparingLong(Item::costNanos).reversed()
                .thenComparingInt(Item::regionId));
            long sum = 0L;
            List<Item> kept = new ArrayList<>();
            for (Item item : entry.getValue()) {
                if (sum + item.costNanos() <= capacity || kept.isEmpty()) {
                    kept.add(item);
                    sum += item.costNanos();
                } else {
                    this.states.get(item.regionId()).lightGroup = -1;
                    loose.add(item);
                }
            }
            entry.setValue(kept);
            load.put(entry.getKey(), sum);
        }

        // First-fit decreasing for the loose ones, into existing groups with room, else a new group.
        loose.sort(Comparator.comparingLong(Item::costNanos).reversed().thenComparingInt(Item::regionId));
        for (Item item : loose) {
            Integer chosen = null;
            for (Map.Entry<Integer, Long> entry : load.entrySet()) {
                if (entry.getValue() + item.costNanos() <= capacity
                    && (chosen == null || entry.getKey() < chosen)) {
                    chosen = entry.getKey();
                }
            }
            if (chosen == null) {
                chosen = this.nextGroupId++;
                load.put(chosen, 0L);
                sticky.put(chosen, new ArrayList<>());
            }
            sticky.computeIfAbsent(chosen, id -> new ArrayList<>()).add(item);
            load.merge(chosen, item.costNanos(), Long::sum);
            this.states.get(item.regionId()).lightGroup = chosen;
        }

        List<Group> built = toGroups(sticky);
        long lightTotal = 0L;
        for (Group group : built) {
            lightTotal += group.costNanos();
        }
        boolean unbalanced = imbalance(built, lightTotal, workers) > this.settings.repackImbalance()
            && built.size() > workers;
        boolean cooled = this.plans - this.lastRepackPlan >= this.settings.repackCooldownTicks();
        if (unbalanced && cooled) {
            this.lastRepackPlan = this.plans;
            this.repacks++;
            for (Item item : light) {
                this.states.get(item.regionId()).lightGroup = -1;
            }
            // One more pass with no stickiness: a clean first-fit-decreasing layout.
            return repackFresh(light, capacity, out);
        }
        out.addAll(built);
        return false;
    }

    private boolean repackFresh(List<Item> light, long capacity, List<Group> out) {
        List<Item> ordered = new ArrayList<>(light);
        ordered.sort(Comparator.comparingLong(Item::costNanos).reversed().thenComparingInt(Item::regionId));
        Map<Integer, List<Item>> groups = new HashMap<>();
        Map<Integer, Long> load = new HashMap<>();
        for (Item item : ordered) {
            Integer chosen = null;
            for (Map.Entry<Integer, Long> entry : load.entrySet()) {
                if (entry.getValue() + item.costNanos() <= capacity
                    && (chosen == null || entry.getKey() < chosen)) {
                    chosen = entry.getKey();
                }
            }
            if (chosen == null) {
                chosen = this.nextGroupId++;
                load.put(chosen, 0L);
                groups.put(chosen, new ArrayList<>());
            }
            groups.get(chosen).add(item);
            load.merge(chosen, item.costNanos(), Long::sum);
            this.states.get(item.regionId()).lightGroup = chosen;
        }
        out.addAll(toGroups(groups));
        return true;
    }

    private static List<Group> toGroups(Map<Integer, List<Item>> byGroup) {
        List<Group> groups = new ArrayList<>(byGroup.size());
        for (List<Item> members : byGroup.values()) {
            if (members.isEmpty()) {
                continue;
            }
            members.sort(Comparator.comparingInt(Item::regionId)); // fixed order inside a task
            long cost = 0L;
            int priority = Integer.MIN_VALUE;
            List<Integer> ids = new ArrayList<>(members.size());
            for (Item item : members) {
                cost += item.costNanos();
                priority = Math.max(priority, item.priority());
                ids.add(item.regionId());
            }
            groups.add(new Group(List.copyOf(ids), cost, priority, false));
        }
        return groups;
    }

    // makespan estimate over ideal: 1.0 means the plan can be spread perfectly over the workers.
    private static double imbalance(List<Group> groups, long total, int workers) {
        if (groups.isEmpty() || total <= 0L) {
            return 1.0;
        }
        long largest = 0L;
        for (Group group : groups) {
            largest = Math.max(largest, group.costNanos());
        }
        double ideal = Math.max((double) total / workers, 1.0);
        return Math.max(largest, ideal) / ideal;
    }

    private void forgetMissing(List<Item> items) {
        Set<Integer> live = new HashSet<>();
        for (Item item : items) {
            live.add(item.regionId());
        }
        this.states.keySet().retainAll(live);
    }
}

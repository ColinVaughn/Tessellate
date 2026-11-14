package org.texboobcat.tessellate.region;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The planner groups regions into scheduler tasks. What matters: every region runs exactly once,
// heavy regions stand alone, light ones share tasks, and the plan does not flap from tick to tick.
class LoadBalancePlannerTest {

    private static long ms(double millis) {
        return (long) (millis * 1_000_000.0);
    }

    private static List<LoadBalancePlanner.Item> items(double... millis) {
        List<LoadBalancePlanner.Item> list = new ArrayList<>();
        for (int i = 0; i < millis.length; i++) {
            list.add(new LoadBalancePlanner.Item(i + 1, ms(millis[i])));
        }
        return list;
    }

    // Short confirmation so tests do not need hundreds of plans; the logic is the same.
    private static LoadBalancePlanner planner(int confirmTicks) {
        return new LoadBalancePlanner(new LoadBalancePlanner.Settings(ms(0.25), 3.0, 0.25, confirmTicks, 1.5, 5));
    }

    private static void assertEveryRegionOnce(LoadBalancePlanner.Plan plan, int count) {
        Set<Integer> seen = new HashSet<>();
        for (LoadBalancePlanner.Group group : plan.groups()) {
            for (int id : group.regionIds()) {
                assertTrue(seen.add(id), "region " + id + " planned twice");
            }
        }
        assertEquals(count, seen.size());
    }

    private static LoadBalancePlanner.Plan settle(LoadBalancePlanner planner, List<LoadBalancePlanner.Item> items,
                                                  int lanes, int plans) {
        LoadBalancePlanner.Plan plan = null;
        for (int i = 0; i < plans; i++) {
            plan = planner.plan(items, lanes);
        }
        return plan;
    }

    @Test
    void everyRegionIsPlannedExactlyOnce() {
        List<LoadBalancePlanner.Item> items = items(30, 0.1, 0.1, 0.2, 5, 0.05, 12);
        LoadBalancePlanner.Plan plan = settle(planner(1), items, 4, 3);
        assertEveryRegionOnce(plan, items.size());
    }

    @Test
    void aHeavyRegionStandsAloneAndStartsFirst() {
        LoadBalancePlanner.Plan plan = settle(planner(1), items(40, 0.1, 0.1, 0.1), 4, 3);
        LoadBalancePlanner.Group first = plan.groups().get(0);
        assertTrue(first.heavy());
        assertEquals(List.of(1), first.regionIds());
    }

    @Test
    void manyTinyRegionsShareAFewTasks() {
        double[] tiny = new double[40];
        java.util.Arrays.fill(tiny, 0.02);
        LoadBalancePlanner.Plan plan = settle(planner(1), items(tiny), 4, 3);
        assertTrue(plan.groups().size() < 40, "40 tiny regions should be coalesced, got " + plan.groups().size());
        assertEveryRegionOnce(plan, 40);
    }

    @Test
    void aRegionDoesNotBecomeHeavyUntilItStaysHeavy() {
        LoadBalancePlanner planner = planner(10);
        List<LoadBalancePlanner.Item> calm = items(0.05, 0.05);
        List<LoadBalancePlanner.Item> hot = items(0.05, 30);
        settle(planner, calm, 2, 5);
        for (int i = 0; i < 9; i++) {
            planner.plan(hot, 2);
        }
        assertEquals(0L, planner.classChanges(), "nine hot plans out of ten must not flip the class yet");
        planner.plan(hot, 2);
        assertEquals(1L, planner.classChanges());
    }

    @Test
    void noiseAroundTheThresholdNeverFlipsTheClass() {
        LoadBalancePlanner planner = planner(3);
        // Total is about 7.3 ms over 2 lanes at 3 tasks per lane, so the threshold is about 1.2 ms
        // and the band reaches 1.5 ms. Region 1 jitters between 1.15 and 1.45 ms, never past the
        // band. Regions 2 and 3 (3 ms) are genuinely heavy and are the only two class changes.
        LoadBalancePlanner.Plan plan = null;
        for (int i = 0; i < 200; i++) {
            double jitter = (i % 2 == 0) ? 1.15 : 1.45;
            plan = planner.plan(items(jitter, 3, 3), 2);
        }
        assertEquals(2L, planner.classChanges());
        for (LoadBalancePlanner.Group group : plan.groups()) {
            if (group.regionIds().contains(1)) {
                assertFalse(group.heavy(), "region 1 only jittered inside the band");
            }
        }
    }

    @Test
    void aSteadyWorkloadProducesTheSamePlanEveryTick() {
        LoadBalancePlanner planner = planner(2);
        List<LoadBalancePlanner.Item> items = items(25, 0.3, 0.2, 0.1, 0.1, 6, 0.05);
        LoadBalancePlanner.Plan settled = settle(planner, items, 4, 10);
        long repacksBefore = planner.repacks();
        for (int i = 0; i < 50; i++) {
            LoadBalancePlanner.Plan next = planner.plan(items, 4);
            assertEquals(settled.groups(), next.groups(), "plan moved without any change in load");
            assertFalse(next.repacked());
        }
        assertEquals(repacksBefore, planner.repacks());
    }

    @Test
    void lightGroupsKeepTheirMembersWhenOneRegionGrowsSlightly() {
        LoadBalancePlanner planner = planner(2);
        List<LoadBalancePlanner.Item> base = items(0.2, 0.2, 0.2, 0.2, 0.2, 0.2, 10);
        LoadBalancePlanner.Plan before = settle(planner, base, 2, 5);
        List<LoadBalancePlanner.Item> grown = items(0.22, 0.2, 0.2, 0.2, 0.2, 0.2, 10);
        LoadBalancePlanner.Plan after = planner.plan(grown, 2);
        assertEquals(groupsOf(before), groupsOf(after));
    }

    private static Set<List<Integer>> groupsOf(LoadBalancePlanner.Plan plan) {
        Set<List<Integer>> groups = new HashSet<>();
        plan.groups().forEach(g -> groups.add(g.regionIds()));
        return groups;
    }

    @Test
    void priorityOrdersTasksBeforeCost() {
        List<LoadBalancePlanner.Item> items = List.of(
            new LoadBalancePlanner.Item(1, ms(50), 0),
            new LoadBalancePlanner.Item(2, ms(30), 5));
        LoadBalancePlanner.Plan plan = settle(planner(1), items, 2, 3);
        assertEquals(List.of(2), plan.groups().get(0).regionIds());
    }

    @Test
    void removedRegionsAreForgotten() {
        LoadBalancePlanner planner = planner(1);
        settle(planner, items(30, 0.1, 0.1), 2, 3);
        LoadBalancePlanner.Plan plan = settle(planner, items(0.1), 2, 2);
        assertEveryRegionOnce(plan, 1);
    }

    @Test
    void emptyInputGivesAnEmptyPlan() {
        assertTrue(planner(1).plan(List.of(), 4).groups().isEmpty());
    }

    @Test
    void identicalInputsGiveIdenticalPlans() {
        List<LoadBalancePlanner.Item> items = items(9, 0.4, 0.3, 4, 0.2, 0.1, 0.1, 7);
        LoadBalancePlanner a = planner(2);
        LoadBalancePlanner b = planner(2);
        for (int i = 0; i < 20; i++) {
            assertEquals(a.plan(items, 3).groups(), b.plan(items, 3).groups());
        }
    }
}

package org.texboobcat.tessellate.region;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// RegionLoad is the measurement half of load balancing: raw per-phase nanoseconds in, a smoothed
// cost out. The smoothing is the behavior, because the balancer must not chase a single slow tick.
class RegionLoadTest {

    private static long ms(double millis) {
        return (long) (millis * 1_000_000.0);
    }

    private static void tick(RegionLoad load, double entityMs, double blockEntityMs, double scheduledMs, int divisor) {
        load.add(RegionLoad.Metric.ENTITY, ms(entityMs));
        load.add(RegionLoad.Metric.BLOCK_ENTITY, ms(blockEntityMs));
        load.add(RegionLoad.Metric.SCHEDULED, ms(scheduledMs));
        load.endTick(ms(50), divisor);
    }

    @Test
    void firstSampleSeedsTheAverageInsteadOfRampingFromZero() {
        RegionLoad load = new RegionLoad(0.2);
        tick(load, 4, 3, 1, 1);
        assertEquals(ms(8), load.cost());
    }

    @Test
    void oneSpikeMovesTheEstimateByAlphaOnly() {
        RegionLoad load = new RegionLoad(0.2);
        for (int i = 0; i < 20; i++) {
            tick(load, 4, 0, 0, 1);
        }
        tick(load, 54, 0, 0, 1); // one 50 ms outlier
        long cost = load.cost();
        assertTrue(cost > ms(13) && cost < ms(15), "a single spike should add about alpha*delta, got " + cost);
    }

    @Test
    void sustainedLoadIsFollowed() {
        RegionLoad load = new RegionLoad(0.2);
        tick(load, 2, 0, 0, 1);
        for (int i = 0; i < 40; i++) {
            tick(load, 20, 0, 0, 1);
        }
        assertTrue(load.cost() > ms(19.5), "estimate should converge on sustained load: " + load.cost());
    }

    @Test
    void aThrottledRegionIsScaledBackToAFullTickEstimate() {
        RegionLoad load = new RegionLoad(0.2);
        tick(load, 2, 1, 0, 4); // only a quarter of the region ran
        assertEquals(ms(12), load.cost());
    }

    @Test
    void wallTimeRecordedForTheTaskWinsWhenItExceedsTheParts() {
        RegionLoad load = new RegionLoad(0.2);
        load.add(RegionLoad.Metric.TICK, ms(10));
        load.add(RegionLoad.Metric.ENTITY, ms(3));
        load.endTick(ms(50), 1);
        assertEquals(ms(10), load.cost());
        assertEquals(0.2, load.average(RegionLoad.Metric.CPU), 1e-9);
    }

    @Test
    void rawCountersAreClearedEachTick() {
        RegionLoad load = new RegionLoad(1.0);
        tick(load, 10, 0, 0, 1);
        load.endTick(ms(50), 1);
        assertEquals(0L, load.cost());
    }

    @Test
    void queueDepthIsAveragedLikeTheOthers() {
        RegionLoad load = new RegionLoad(0.5);
        load.setQueueDepth(8);
        load.endTick(ms(50), 1);
        load.setQueueDepth(0);
        load.endTick(ms(50), 1);
        assertEquals(4.0, load.average(RegionLoad.Metric.QUEUE), 1e-9);
    }

    @Test
    void learnedPerItemCostPredictsAfterTheCountsChange() {
        RegionLoad load = new RegionLoad(0.2);
        load.setCounts(100, 0);
        load.add(RegionLoad.Metric.ENTITY, ms(10)); // 0.1 ms per entity
        load.endTick(ms(50), 1);
        load.setCounts(300, 0);
        load.endTick(ms(50), 1); // the counts tripled; the time average has not caught up
        assertEquals(ms(30), load.predictedCost(), ms(0.5));
    }

    @Test
    void aNewRegionAfterAMergeInheritsTheCombinedLoad() {
        RegionLoad a = new RegionLoad();
        RegionLoad b = new RegionLoad();
        tick(a, 5, 0, 0, 1);
        tick(b, 7, 0, 0, 1);
        RegionLoad merged = new RegionLoad();
        merged.seedFromMerged(List.of(a, b));
        assertEquals(ms(12), merged.cost());
    }

    @Test
    void aSplitOffRegionInheritsItsShareOfTheLoad() {
        RegionLoad parent = new RegionLoad();
        tick(parent, 10, 0, 0, 1);
        RegionLoad child = new RegionLoad();
        child.seedFrom(parent, 0.25);
        assertEquals(ms(2.5), child.cost());
    }

    @Test
    void aSurvivorKeepsItsOwnLoadAndTakesOnTheAbsorbedOne() {
        RegionLoad survivor = new RegionLoad();
        RegionLoad absorbed = new RegionLoad();
        tick(survivor, 6, 0, 0, 1);
        tick(absorbed, 2, 0, 0, 1);
        survivor.absorb(absorbed);
        assertEquals(ms(8), survivor.cost());
        // The next real sample blends with the combined history instead of replacing it.
        tick(survivor, 8, 0, 0, 1);
        assertEquals(ms(8), survivor.cost());
    }

    @Test
    void absorbingAveragesPerItemCostByItemCount() {
        RegionLoad a = new RegionLoad(1.0);
        a.setCounts(100, 0);
        a.add(RegionLoad.Metric.ENTITY, ms(10)); // 0.1 ms each
        a.endTick(ms(50), 1);
        RegionLoad b = new RegionLoad(1.0);
        b.setCounts(300, 0);
        b.add(RegionLoad.Metric.ENTITY, ms(60)); // 0.2 ms each
        b.endTick(ms(50), 1);
        a.absorb(b);
        assertEquals(400, a.entityCount());
        // (100 * 0.1 + 300 * 0.2) / 400 = 0.175 ms per entity
        assertEquals(ms(70), a.predictedCost(), ms(0.5));
    }

    @Test
    void aSplitDividesTheLoadBetweenTheOriginalAndTheNewRegion() {
        RegionLoad original = new RegionLoad();
        tick(original, 10, 0, 0, 1);
        RegionLoad child = new RegionLoad();
        child.seedFrom(original, 0.3);
        original.scale(0.7);
        assertEquals(ms(10), original.cost() + child.cost());
    }
}

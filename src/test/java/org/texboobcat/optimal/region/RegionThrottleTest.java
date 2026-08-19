package org.texboobcat.optimal.region;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// How the throttle sizes divisors, and in particular how it accounts for parallel execution.
//
// The projection model is the whole behavior. Serially a tick costs the sum of its regions; in
// parallel it costs the makespan. Using the serial sum while regions run in parallel throttles
// regions that comfortably fit, and hides the entire benefit of running them in parallel.
//
// Inputs are passed explicitly rather than read from Config, which builds a
// ModConfigSpec in its static initializer and cannot be loaded outside a running game.
class RegionThrottleTest {

    private static final long BUDGET = ms(25.0);
    private static final long FLOOR = ms(2.0);
    private static final int MAX_DIVISOR = 16;

    private static long ms(double millis) {
        return (long) (millis * 1_000_000.0);
    }

    private static List<Region> regionsCosting(double... millis) {
        List<Region> regions = new ArrayList<>();
        for (int i = 0; i < millis.length; i++) {
            Region region = new Region(i + 1, 2);
            region.updateCost(ms(millis[i]), 0L);
            regions.add(region);
        }
        return regions;
    }

    private static void throttle(List<Region> regions, int lanes) {
        RegionThrottle.apply(regions, true, BUDGET, FLOOR, MAX_DIVISOR, lanes);
    }

    @Test
    void regionsInsideTheBudgetAreNotThrottled() {
        List<Region> regions = regionsCosting(5.0, 5.0, 5.0);
        throttle(regions, 1);
        for (Region region : regions) {
            assertEquals(1, region.tickDivisor());
        }
    }

    @Test
    void theWorstOffenderIsThrottledFirst() {
        List<Region> regions = regionsCosting(1.0, 40.0, 1.0);
        throttle(regions, 1);
        assertTrue(regions.get(1).tickDivisor() > 1, "the expensive region should be throttled");
        assertEquals(1, regions.get(0).tickDivisor());
        assertEquals(1, regions.get(2).tickDivisor());
    }

    @Test
    void cheapRegionsAreNeverThrottled() {
        // Below the floor, so measurement noise must not be able to slow them.
        List<Region> regions = regionsCosting(0.5, 0.5, 200.0);
        throttle(regions, 1);
        assertEquals(1, regions.get(0).tickDivisor());
        assertEquals(1, regions.get(1).tickDivisor());
    }

    @Test
    void divisorIsCappedRatherThanHalvedForever() {
        List<Region> regions = regionsCosting(10_000.0);
        throttle(regions, 1);
        assertTrue(regions.get(0).tickDivisor() <= MAX_DIVISOR);
    }

    @Test
    void nonPowerOfTwoCapIsHonoredExactly() {
        List<Region> regions = regionsCosting(10_000.0);
        RegionThrottle.apply(regions, true, BUDGET, FLOOR, 3, 1);
        assertEquals(3, regions.get(0).tickDivisor());
    }

    @Test
    void cappedRegionDoesNotPreventThrottlingOtherRegions() {
        List<Region> regions = regionsCosting(10_000.0, 1_000.0);
        RegionThrottle.apply(regions, true, ms(700.0), FLOOR, MAX_DIVISOR, 1);
        assertTrue(RegionThrottle.projectedTickNanos(regions, 1) <= ms(700.0),
            "the remaining region can still be throttled enough to meet the budget");
    }

    @Test
    void throttlingOffLeavesEveryRegionAtFullRate() {
        List<Region> regions = regionsCosting(500.0, 500.0);
        RegionThrottle.apply(regions, false, BUDGET, FLOOR, MAX_DIVISOR, 1);
        for (Region region : regions) {
            assertEquals(1, region.tickDivisor());
        }
    }

    // ---- the projection model ----------------------------------------------------------------

    @Test
    void serialProjectionIsTheSum() {
        assertEquals(ms(18.0), RegionThrottle.projectedTickNanos(regionsCosting(6.0, 6.0, 6.0), 1));
    }

    @Test
    void parallelProjectionSpreadsAcrossLanes() {
        // Three equal regions on three lanes cost one region's worth of wall clock, not three.
        assertEquals(ms(6.0), RegionThrottle.projectedTickNanos(regionsCosting(6.0, 6.0, 6.0), 3));
    }

    @Test
    void parallelProjectionIsBoundedBelowByTheLargestRegion() {
        // A region cannot be split across threads, so extra lanes cannot take the projection below
        // its cost. Without this bound the throttle would leave one region overrunning the budget
        // on its own and believe the tick was fine.
        assertEquals(ms(40.0), RegionThrottle.projectedTickNanos(regionsCosting(40.0, 1.0, 1.0), 8));
    }

    @Test
    void projectionAccountsForDivisorsAlreadyApplied() {
        List<Region> regions = regionsCosting(20.0, 20.0);
        regions.get(0).setTickDivisor(4);
        assertEquals(ms(25.0), RegionThrottle.projectedTickNanos(regions, 1));
    }

    // ---- the result that matters -------------------------------------------------------------

    @Test
    void parallelismLetsRegionsRunThatSerialExecutionWouldThrottle() {
        // Four regions of 9 ms is 36 ms serially, over a 25 ms budget, so serial execution has to
        // slow some of them down.
        List<Region> serial = regionsCosting(9.0, 9.0, 9.0, 9.0);
        throttle(serial, 1);
        assertTrue(serial.stream().anyMatch(region -> region.tickDivisor() > 1),
            "four 9 ms regions exceed a 25 ms budget when summed");

        // The same four across four lanes are 9 ms of wall clock, which fits, so none is slowed.
        List<Region> parallel = regionsCosting(9.0, 9.0, 9.0, 9.0);
        throttle(parallel, 4);
        for (Region region : parallel) {
            assertEquals(1, region.tickDivisor(),
                "region " + region.id() + " should run at full rate on its own lane");
        }
    }

    @Test
    void oneOverloadedRegionIsStillThrottledNoMatterHowManyLanes() {
        // Parallelism must not become an excuse to stop isolating: lanes cannot help a single
        // region that is itself over budget.
        List<Region> regions = regionsCosting(200.0, 1.0, 1.0, 1.0);
        throttle(regions, 16);
        assertTrue(regions.get(0).tickDivisor() > 1,
            "a single over-budget region must still be throttled");
    }

    // ---- the budget the throttle is given -----------------------------------------------------

    @Test
    void aHealthyServerGetsAlmostTheWholeTargetAsBudget() {
        // 41 ms tick, 35 ms of it region work, so 6 ms of overhead the regions cannot control.
        // A 45 ms target leaves them 39 ms - more than they are using, so nothing is throttled.
        long budget = RegionThrottle.effectiveBudgetNanos(ms(41.0), ms(35.0), ms(45.0), ms(25.0));
        assertEquals(ms(39.0), budget);

        List<Region> regions = regionsCosting(35.0);
        RegionThrottle.apply(regions, true, budget, FLOOR, MAX_DIVISOR, 1);
        assertEquals(1, regions.get(0).tickDivisor(),
            "a server holding 20 TPS at 41 ms must not have its regions slowed down");
    }

    @Test
    void anOverloadedServerGetsATighterBudget() {
        // 89 ms tick, 85 ms of region work: 4 ms overhead, so regions may have 41 of the 45.
        long budget = RegionThrottle.effectiveBudgetNanos(ms(89.0), ms(85.0), ms(45.0), ms(25.0));
        assertEquals(ms(41.0), budget);

        List<Region> regions = regionsCosting(85.0);
        RegionThrottle.apply(regions, true, budget, FLOOR, MAX_DIVISOR, 1);
        assertTrue(regions.get(0).tickDivisor() > 1,
            "a region costing 85 ms of a 45 ms target must be slowed");
    }

    @Test
    void overheadAloneCanNeverSqueezeRegionsBelowTheFloor() {
        // Something outside the regions is eating the whole tick. Regions still get the floor
        // rather than being throttled into looking frozen for a problem that is not theirs.
        assertEquals(ms(25.0),
            RegionThrottle.effectiveBudgetNanos(ms(120.0), ms(5.0), ms(45.0), ms(25.0)));
    }

    @Test
    void budgetFallsBackToTheFloorBeforeAnyTickIsMeasured() {
        assertEquals(ms(25.0), RegionThrottle.effectiveBudgetNanos(0L, 0L, ms(45.0), ms(25.0)));
    }

    @Test
    void regionWorkExceedingTheMeasuredTickIsTreatedAsZeroOverhead() {
        // Region cost is an estimate scaled by the divisor, so it can exceed the measured tick.
        // That must not produce negative overhead and a budget larger than the target.
        assertEquals(ms(45.0),
            RegionThrottle.effectiveBudgetNanos(ms(30.0), ms(50.0), ms(45.0), ms(25.0)));
    }

    // parallelLanes is deliberately not tested here: it reads Config, which cannot be loaded
    // outside a running game. It is the wiring, and the lane count it produces is covered above
    // by passing lanes explicitly.
}

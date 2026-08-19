package org.texboobcat.optimal.guard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.texboobcat.optimal.region.Region;
import org.texboobcat.optimal.region.RegionSectionPos;
import org.texboobcat.optimal.region.Regionizer;
import org.texboobcat.optimal.region.RegionizerListener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The shard-write ownership rule.
//
// The rule has to allow exactly one ring of spill and no more. Too strict and every entity that
// walks over a cell boundary is reported as a violation, which would bury a real one in noise. Too
// loose and the check stops catching the case it exists for.
class ShardOwnershipTest {

    private ViolationLog log;

    @BeforeEach
    void setUp() {
        log = new ViolationLog();
        RegionThreadContext.clear();
    }

    @AfterEach
    void tearDown() {
        RegionThreadContext.clear();
    }

    // A region owning the single cell containing chunk (0, 0), i.e. cell [0, 0].
    private static Region regionOwningOriginCell() {
        Regionizer regionizer = new Regionizer(2, 2, RegionizerListener.NOOP);
        regionizer.queueAddChunk(0, 0);
        regionizer.update();
        return regionizer.regionForChunk(0, 0);
    }

    private static long cell(int x, int z) {
        return RegionSectionPos.pack(x, z);
    }

    @Test
    void mainThreadMayWriteAnyCell() {
        assertTrue(Ownership.checkRegionCell(cell(1000, 1000), log, false));
        assertEquals(0L, log.total());
    }

    @Test
    void aRegionMayWriteItsOwnCell() {
        Region region = regionOwningOriginCell();
        RegionThreadContext.enter(region, "minecraft:overworld");

        assertTrue(Ownership.checkRegionCell(cell(0, 0), log, false));
        assertEquals(0L, log.total());
    }

    @Test
    void aRegionMayWriteOneCellBeyondItsOwn() {
        // An entity the region ticks can step across a cell boundary, and the write lands in the
        // neighboring shard. All eight neighbors are legitimate.
        Region region = regionOwningOriginCell();
        RegionThreadContext.enter(region, "minecraft:overworld");

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                assertTrue(Ownership.checkRegionCell(cell(dx, dz), log, false),
                    "neighbor [" + dx + ", " + dz + "] should be writable");
            }
        }
        assertEquals(0L, log.total());
    }

    @Test
    void aRegionMayNotWriteTwoCellsBeyondItsOwn() {
        // Two cells out is past anything one tick of movement can reach, so it means the
        // separation argument has failed somewhere.
        Region region = regionOwningOriginCell();
        RegionThreadContext.enter(region, "minecraft:overworld");

        assertFalse(Ownership.checkRegionCell(cell(2, 0), log, false));
        assertFalse(Ownership.checkRegionCell(cell(0, -2), log, false));
        assertFalse(Ownership.checkRegionCell(cell(2, 2), log, false));
        assertEquals(3L, log.total());
    }

    @Test
    void strictModeThrowsInsteadOfLogging() {
        Region region = regionOwningOriginCell();
        RegionThreadContext.enter(region, "minecraft:overworld");

        assertThrows(RegionViolationException.class,
            () -> Ownership.checkRegionCell(cell(9, 9), log, true));
        assertEquals(0L, log.total(), "strict mode throws rather than recording");
    }

    @Test
    void violationNamesTheCellAndTheRegion() {
        Region region = regionOwningOriginCell();
        RegionThreadContext.enter(region, "minecraft:overworld");

        Ownership.checkRegionCell(cell(5, -7), log, false);

        assertEquals(1, log.entries().size());
        ViolationLog.Entry entry = log.entries().get(0);
        assertEquals(region.id(), entry.regionId());
        assertEquals("minecraft:overworld", entry.levelKey());
        assertEquals(5, entry.chunkX());
        assertEquals(-7, entry.chunkZ());
        assertEquals(Ownership.Mode.WRITE, entry.mode());
    }

    @Test
    void repeatedViolationsFromOneSiteCollapse() {
        Region region = regionOwningOriginCell();
        RegionThreadContext.enter(region, "minecraft:overworld");

        for (int i = 0; i < 5; i++) {
            Ownership.checkRegionCell(cell(20, 20), log, false);
        }
        assertEquals(5L, log.total());
        assertEquals(1, log.distinctSites(), "one call site should not produce five entries");
    }
}

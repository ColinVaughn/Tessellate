package org.texboobcat.tessellate.guard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.texboobcat.tessellate.region.Region;
import org.texboobcat.tessellate.region.Regionizer;
import org.texboobcat.tessellate.region.RegionizerListener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnershipTest {

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

    // Matches ChunkPos.asLong: x low, z high.
    private static long chunk(int x, int z) {
        return ((long) z << 32) | (x & 0xFFFFFFFFL);
    }

    // Builds a region the same way the server does, through the regionizer, rather than reaching
    // past the package boundary that keeps section ownership under the regionizer's control.
    private static Region regionOwningOrigin() {
        Regionizer regionizer = new Regionizer(2, 2, RegionizerListener.NOOP);
        regionizer.queueAddChunk(0, 0);
        regionizer.update();
        return regionizer.regionForChunk(0, 0);
    }

    @Test
    void mainThreadIsAlwaysAllowed() {
        assertNull(RegionThreadContext.current());
        assertTrue(Ownership.check("overworld", chunk(999, 999), Ownership.Mode.WRITE, log, false));
        assertEquals(0, log.total());
    }

    @Test
    void regionOwningTheChunkIsAllowed() {
        RegionThreadContext.enter(regionOwningOrigin(), "overworld");

        assertTrue(Ownership.check("overworld", chunk(0, 0), Ownership.Mode.WRITE, log, false));
        assertTrue(Ownership.check("overworld", chunk(3, 3), Ownership.Mode.WRITE, log, false));
        assertEquals(0, log.total());
    }

    @Test
    void readOfAnUnownedChunkIsAllowed() {
        RegionThreadContext.enter(regionOwningOrigin(), "overworld");

        assertTrue(Ownership.check("overworld", chunk(100, 100), Ownership.Mode.READ, log, false));
        assertEquals(0, log.total());
    }

    @Test
    void writeToAnUnownedChunkIsAViolation() {
        RegionThreadContext.enter(regionOwningOrigin(), "overworld");

        assertFalse(Ownership.check("overworld", chunk(100, 100), Ownership.Mode.WRITE, log, false));
        assertEquals(1, log.total());
    }

    @Test
    void anyAccessToAnotherDimensionIsAViolation() {
        RegionThreadContext.enter(regionOwningOrigin(), "overworld");

        assertFalse(Ownership.check("the_nether", chunk(0, 0), Ownership.Mode.WRITE, log, false));
        assertFalse(Ownership.check("the_nether", chunk(0, 0), Ownership.Mode.READ, log, false));
        assertEquals(2, log.total());
    }

    @Test
    void strictModeThrows() {
        RegionThreadContext.enter(regionOwningOrigin(), "overworld");

        assertThrows(RegionViolationException.class,
            () -> Ownership.check("overworld", chunk(100, 100), Ownership.Mode.WRITE, log, true));
    }

    @Test
    void exitRestoresMainThreadSemantics() {
        RegionThreadContext.enter(regionOwningOrigin(), "overworld");
        RegionThreadContext.exit();

        assertNull(RegionThreadContext.current());
        assertTrue(Ownership.check("overworld", chunk(100, 100), Ownership.Mode.WRITE, log, false));
    }

    @Test
    void violationLogDeduplicatesBySite() {
        RegionThreadContext.enter(regionOwningOrigin(), "overworld");

        for (int i = 0; i < 50; i++) {
            Ownership.check("overworld", chunk(100, 100), Ownership.Mode.WRITE, log, false);
        }
        assertEquals(50, log.total());
        assertEquals(1, log.distinctSites(), "identical call sites collapse to one recorded entry");
        assertEquals(50, log.entries().get(0).count());
    }

    @Test
    void violationLogRecordsTheOffendingRegionAndChunk() {
        Region region = regionOwningOrigin();
        RegionThreadContext.enter(region, "overworld");

        Ownership.check("overworld", chunk(100, 100), Ownership.Mode.WRITE, log, false);

        ViolationLog.Entry entry = log.entries().get(0);
        assertEquals(region.id(), entry.regionId());
        assertEquals("overworld", entry.levelKey());
        assertEquals(100, entry.chunkX());
        assertEquals(100, entry.chunkZ());
        assertEquals(Ownership.Mode.WRITE, entry.mode());
        assertTrue(entry.count() >= 1);
        assertTrue(entry.site().contains("OwnershipTest"),
            "call site should name the caller, not the guard package, but was: " + entry.site());
    }

    @Test
    void contextIsPerThread() throws Exception {
        RegionThreadContext.enter(regionOwningOrigin(), "overworld");

        boolean[] otherThreadSawMainThread = new boolean[1];
        Thread other = new Thread(() -> otherThreadSawMainThread[0] = RegionThreadContext.onMainThread());
        other.start();
        other.join();

        assertTrue(otherThreadSawMainThread[0], "a region binding must not leak to another thread");
        assertFalse(RegionThreadContext.onMainThread());
    }
}

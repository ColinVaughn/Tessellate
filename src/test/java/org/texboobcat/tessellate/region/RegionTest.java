package org.texboobcat.tessellate.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionTest {

    @Test
    void newRegionIsEmpty() {
        Region r = new Region(1, 2);
        assertEquals(1, r.id());
        assertEquals(0, r.sectionCount());
        assertTrue(r.sections().isEmpty());
    }

    @Test
    void ownsSectionAfterAdd() {
        Region r = new Region(1, 2);
        r.addSection(RegionSectionPos.pack(4, 4));
        assertTrue(r.ownsSection(RegionSectionPos.pack(4, 4)));
        assertFalse(r.ownsSection(RegionSectionPos.pack(4, 5)));
        assertEquals(1, r.sectionCount());
    }

    @Test
    void sectionViewIsReadOnly() {
        Region r = new Region(1, 2);
        assertThrows(UnsupportedOperationException.class,
            () -> r.sections().add(RegionSectionPos.pack(4, 4)));
    }

    @Test
    void ownsChunkMapsThroughTheShift() {
        Region r = new Region(1, 2);
        r.addSection(RegionSectionPos.pack(0, 0));
        // shift 2: chunks 0..3 land in section 0
        assertTrue(r.ownsChunk(0, 0));
        assertTrue(r.ownsChunk(3, 3));
        assertFalse(r.ownsChunk(4, 0));
        assertFalse(r.ownsChunk(-1, 0));
    }

    @Test
    void removeSectionDropsOwnership() {
        Region r = new Region(1, 2);
        long s = RegionSectionPos.pack(7, 7);
        r.addSection(s);
        r.removeSection(s);
        assertFalse(r.ownsSection(s));
        assertEquals(0, r.sectionCount());
    }

    @Test
    void recordsTheLastTickCost() {
        Region r = new Region(1, 2);
        r.recordTick(1_000_000L);
        r.recordTick(3_000_000L);
        assertEquals(3_000_000L, r.lastTickNanos());
    }

    @Test
    void recordsTheLastExecutionThreadForDiagnostics() {
        Region r = new Region(1, 2);
        r.recordExecutionThread("tessellate-region-7", 7);
        assertEquals("tessellate-region-7", r.lastThreadName());
        assertEquals(7, r.lastWorkerIndex());
    }
}

package org.texboobcat.tessellate.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Shard selection for bounding-box queries.
//
// The failure this guards against is silent: selecting one cell too few omits the entities in
// the shard that was never visited, with no exception and no log line.
class RegionShardBoundsTest {

    private static final int SHIFT = 2; // 4x4 chunks per cell, i.e. 64 blocks

    @Test
    void sectionCoordUsesFloorNotTruncation() {
        // Truncating -0.5 to 0 would put it in section 0; it belongs in section -1.
        assertEquals(-1, RegionShardBounds.sectionCoord(-0.5));
        assertEquals(-1, RegionShardBounds.sectionCoord(-1.0));
        assertEquals(-1, RegionShardBounds.sectionCoord(-16.0));
        assertEquals(-2, RegionShardBounds.sectionCoord(-16.5));
        assertEquals(0, RegionShardBounds.sectionCoord(0.0));
        assertEquals(0, RegionShardBounds.sectionCoord(15.9));
        assertEquals(1, RegionShardBounds.sectionCoord(16.0));
    }

    @Test
    void cellCoordUsesArithmeticShiftNotDivision() {
        assertEquals(0, RegionShardBounds.cellCoord(3, SHIFT));
        assertEquals(1, RegionShardBounds.cellCoord(4, SHIFT));
        // -1 / 4 == 0 would wrongly fold section -1 into cell 0.
        assertEquals(-1, RegionShardBounds.cellCoord(-1, SHIFT));
        assertEquals(-1, RegionShardBounds.cellCoord(-4, SHIFT));
        assertEquals(-2, RegionShardBounds.cellCoord(-5, SHIFT));
    }

    @Test
    void cellMappingAgreesWithTheRegionizer() {
        // A section coordinate is a chunk coordinate, so shard routing and region ownership must
        // land on the same cell or a region would own cells its entities are not stored in.
        for (int chunk = -40; chunk <= 40; chunk++) {
            long viaRegionizer = RegionSectionPos.fromChunk(chunk, chunk, SHIFT);
            long viaShardBounds = RegionSectionPos.pack(
                RegionShardBounds.cellCoord(chunk, SHIFT),
                RegionShardBounds.cellCoord(chunk, SHIFT));
            assertEquals(viaRegionizer, viaShardBounds, "chunk " + chunk);
        }
    }

    @Test
    void smallBoxInsideOneCellSelectsOneShard() {
        // A cell at shift 2 spans 64 blocks. Well inside cell 0, even after the margin.
        double min = 20.0;
        double max = 21.8;
        assertEquals(0, RegionShardBounds.minCell(min, SHIFT));
        assertEquals(0, RegionShardBounds.maxCell(max, SHIFT));
    }

    @Test
    void boxAtACellBoundarySelectsBothShards() {
        // Cell 0 covers blocks 0..63, cell 1 starts at 64. A box ending at 63.5 reaches into
        // cell 1 only because of the 2-block margin - which is exactly the case that silently
        // drops entities if the margin is forgotten.
        assertEquals(0, RegionShardBounds.minCell(63.5, SHIFT));
        assertEquals(1, RegionShardBounds.maxCell(63.5, SHIFT));
    }

    @Test
    void marginIsAppliedOnBothSides() {
        // 64.0 is the first block of cell 1; minus the margin it reaches back into cell 0.
        assertEquals(0, RegionShardBounds.minCell(64.0, SHIFT));
        assertEquals(1, RegionShardBounds.maxCell(64.0, SHIFT));
    }

    @Test
    void negativeCoordinatesSelectNegativeCells() {
        // Blocks -64..-1 are cell -1; -65 is cell -2.
        assertEquals(-1, RegionShardBounds.maxCell(-10.0, SHIFT));
        assertEquals(-1, RegionShardBounds.minCell(-10.0, SHIFT));
        assertEquals(-2, RegionShardBounds.minCell(-64.0, SHIFT));
    }

    @Test
    void selectedRangeCoversEveryCellVanillaWouldVisit() {
        // Exhaustive cross-check against the rule the vanilla traversal uses: every section it
        // would look at must fall inside the selected cell range.
        for (double min = -80.0; min <= 80.0; min += 0.5) {
            double max = min + 3.0;
            int minCell = RegionShardBounds.minCell(min, SHIFT);
            int maxCell = RegionShardBounds.maxCell(max, SHIFT);
            assertTrue(minCell <= maxCell, "empty range at " + min);

            int firstSection = RegionShardBounds.sectionCoord(min - RegionShardBounds.HORIZONTAL_MARGIN);
            int lastSection = RegionShardBounds.sectionCoord(max + RegionShardBounds.HORIZONTAL_MARGIN);
            for (int section = firstSection; section <= lastSection; section++) {
                int cell = RegionShardBounds.cellCoord(section, SHIFT);
                assertTrue(cell >= minCell && cell <= maxCell,
                    "section " + section + " (cell " + cell + ") outside range "
                        + minCell + ".." + maxCell + " for box starting at " + min);
            }
        }
    }

    @Test
    void shiftZeroGivesOneShardPerChunk() {
        assertEquals(5, RegionShardBounds.cellCoord(5, 0));
        assertEquals(-3, RegionShardBounds.cellCoord(-3, 0));
    }
}

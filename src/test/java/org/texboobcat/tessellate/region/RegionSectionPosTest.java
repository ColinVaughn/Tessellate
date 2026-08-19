package org.texboobcat.tessellate.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RegionSectionPosTest {

    @Test
    void packAndUnpackRoundTrip() {
        long p = RegionSectionPos.pack(5, -9);
        assertEquals(5, RegionSectionPos.x(p));
        assertEquals(-9, RegionSectionPos.z(p));
    }

    @Test
    void packHandlesExtremeCoordinates() {
        long p = RegionSectionPos.pack(Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MIN_VALUE, RegionSectionPos.x(p));
        assertEquals(Integer.MAX_VALUE, RegionSectionPos.z(p));
    }

    @Test
    void distinctCoordinatesPackDistinctly() {
        assertNotEquals(RegionSectionPos.pack(1, 0), RegionSectionPos.pack(0, 1));
        assertNotEquals(RegionSectionPos.pack(-1, 0), RegionSectionPos.pack(0, -1));
    }

    @Test
    void chunkToSectionUsesArithmeticShiftNotDivision() {
        // shift 2 == 4x4 chunk sections.
        // -1 / 4 == 0 would wrongly fold chunk -1 into section 0.
        assertEquals(-1, RegionSectionPos.x(RegionSectionPos.fromChunk(-1, -1, 2)));
        assertEquals(-1, RegionSectionPos.z(RegionSectionPos.fromChunk(-1, -1, 2)));
        assertEquals(-1, RegionSectionPos.x(RegionSectionPos.fromChunk(-4, 0, 2)));
        assertEquals(-2, RegionSectionPos.x(RegionSectionPos.fromChunk(-5, 0, 2)));
        assertEquals(0, RegionSectionPos.x(RegionSectionPos.fromChunk(3, 0, 2)));
        assertEquals(1, RegionSectionPos.x(RegionSectionPos.fromChunk(4, 0, 2)));
    }

    @Test
    void fromChunkLongMatchesFromChunkInts() {
        // Same encoding Minecraft's ChunkPos.asLong uses: x in the low bits, z in the high bits.
        long chunkPos = ((long) 13 << 32) | (-7 & 0xFFFFFFFFL);
        assertEquals(
            RegionSectionPos.fromChunk(-7, 13, 2),
            RegionSectionPos.fromChunkLong(chunkPos, 2));
    }

    @Test
    void chebyshevDistanceIsMaxOfAxes() {
        assertEquals(0, RegionSectionPos.chebyshev(RegionSectionPos.pack(3, 3), RegionSectionPos.pack(3, 3)));
        assertEquals(3, RegionSectionPos.chebyshev(RegionSectionPos.pack(0, 0), RegionSectionPos.pack(3, 1)));
        assertEquals(4, RegionSectionPos.chebyshev(RegionSectionPos.pack(-2, 0), RegionSectionPos.pack(2, 0)));
    }
}

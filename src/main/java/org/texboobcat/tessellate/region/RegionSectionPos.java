package org.texboobcat.tessellate.region;

// Packed 2D section coordinates.
//
// A section is a 2^shift x 2^shift block of chunks. Section membership is the
// unit of region ownership; chunks are never assigned to regions individually.
//
// Contains no Minecraft types so that the regionizer is unit-testable with no game context.
public final class RegionSectionPos {

    private RegionSectionPos() {
    }

    public static long pack(int sectionX, int sectionZ) {
        return ((long) sectionX << 32) | (sectionZ & 0xFFFFFFFFL);
    }

    public static int x(long packed) {
        return (int) (packed >> 32);
    }

    public static int z(long packed) {
        return (int) packed;
    }

    // Section containing the given chunk. Uses an arithmetic shift, not division: chunk -1 with
    // shift 2 must land in section -1, whereas -1 / 4 would wrongly give section 0.
    public static long fromChunk(int chunkX, int chunkZ, int shift) {
        return pack(chunkX >> shift, chunkZ >> shift);
    }

    // Section containing the chunk encoded by net.minecraft.world.level.ChunkPos#asLong,
    // which stores x in the low 32 bits and z in the high 32 bits.
    public static long fromChunkLong(long chunkPos, int shift) {
        return fromChunk((int) chunkPos, (int) (chunkPos >> 32), shift);
    }

    // Chebyshev (chessboard) distance, the metric that defines section adjacency.
    public static int chebyshev(long a, long b) {
        return Math.max(Math.abs(x(a) - x(b)), Math.abs(z(a) - z(b)));
    }

    public static String toString(long packed) {
        return "[" + x(packed) + ", " + z(packed) + "]";
    }
}

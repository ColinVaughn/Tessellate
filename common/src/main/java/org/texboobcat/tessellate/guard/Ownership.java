package org.texboobcat.tessellate.guard;

import org.texboobcat.tessellate.region.Region;
import org.texboobcat.tessellate.region.RegionSectionPos;

// The thread-ownership assertion layer.
//
// Resolution order, from the design:
//
// - main thread -> allowed
// - current region owns the chunk -> allowed
// - read of a chunk the region does not own, in its own level -> allowed, because the
// separation buffer between regions is read-shared
// - anything else -> violation
public final class Ownership {

    public enum Mode {
        READ,
        WRITE
    }

    private Ownership() {
    }

    // Returns true if the access is permitted. In degrade mode a violation returns false but does
    // not throw, because aborting mid-operation would leave partially applied state.
    public static boolean check(String levelKey, long chunkPos, Mode mode, ViolationLog log, boolean strict) {
        RegionThreadContext.Binding binding = RegionThreadContext.currentBinding();
        if (binding == null) {
            return true;
        }

        Region region = binding.region();
        boolean sameLevel = binding.levelKey().equals(levelKey);
        if (sameLevel && region.ownsChunkLong(chunkPos)) {
            return true;
        }
        if (sameLevel && mode == Mode.READ) {
            return true;
        }

        int chunkX = (int) chunkPos;
        int chunkZ = (int) (chunkPos >> 32);
        String site = callSite();

        String message = "region#" + region.id() + " (" + binding.levelKey() + ") performed a "
            + mode + " on " + levelKey + " chunk [" + chunkX + ", " + chunkZ
            + "] it does not own, at " + site;

        if (strict) {
            throw new RegionViolationException(message);
        }
        log.record(region.id(), levelKey, chunkX, chunkZ, mode, site);
        return false;
    }

    // Whether the current thread may write to the entity-storage shard for cell.
    //
    // Sharding gives a region exclusive ownership of its cells, but a worker legitimately writes
    // one cell beyond them: an entity it ticks can step across a cell boundary, and the write lands
    // in the neighboring shard. Two regions cannot both reach the same
    // neighbor because the merge radius keeps distinct regions at least three cells apart while
    // a tick's movement spills at most one. That is a bounded argument rather than an
    // unconditional one, so it is checked rather than trusted.
    //
    // Reads are not checked. The buffer between regions is read-shared by design, and the read
    // path is the hottest in the game.
    //
    // Returns true if the access is permitted
    public static boolean checkRegionCell(long cell, ViolationLog log, boolean strict) {
        RegionThreadContext.Binding binding = RegionThreadContext.currentBinding();
        if (binding == null) {
            return true;
        }

        Region region = binding.region();
        if (region.ownsSection(cell) || ownsNeighbourOf(region, cell)) {
            return true;
        }

        int cellX = RegionSectionPos.x(cell);
        int cellZ = RegionSectionPos.z(cell);
        String site = callSite();
        String message = "region#" + region.id() + " (" + binding.levelKey()
            + ") wrote entity storage for cell " + RegionSectionPos.toString(cell)
            + ", which is neither its own nor adjacent to it, at " + site;

        if (strict) {
            throw new RegionViolationException(message);
        }
        log.record(region.id(), binding.levelKey(), cellX, cellZ, Mode.WRITE, site);
        return false;
    }

    private static boolean ownsNeighbourOf(Region region, long cell) {
        int cellX = RegionSectionPos.x(cell);
        int cellZ = RegionSectionPos.z(cell);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0)
                    && region.ownsSection(RegionSectionPos.pack(cellX + dx, cellZ + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    // Reports the main thread touching state a region worker is currently using.
    //
    // The mirror image of checkRegionCell, and the one that matters for enabling
    // parallel ticking. With independent region loops there is no phase in which no worker is
    // running, so every main-thread code path that touches region-owned state has to lease the
    // region first. Enumerating those paths by reading the server is hopeless; this makes the
    // server enumerate them, the same way the entity-section diagnostic found the original blocker.
    public static void reportMainThreadRace(String levelKey, int regionId, long cell,
                                            ViolationLog log, boolean strict) {
        String site = callSite();
        String message = "main thread touched cell " + RegionSectionPos.toString(cell)
            + " while region#" + regionId + " (" + levelKey + ") was still ticking it, at " + site;

        if (strict) {
            throw new RegionViolationException(message);
        }
        log.record(regionId, levelKey, RegionSectionPos.x(cell), RegionSectionPos.z(cell),
            Mode.WRITE, site);
    }

    private static final String SELF = Ownership.class.getName();

    // First stack frame outside this class, which is the code that actually made the access.
    //
    // Filtered on this class rather than on a package prefix: a prefix filter would also skip
    // legitimate callers that happen to live in the same package.
    private static String callSite() {
        return StackWalker.getInstance()
            .walk(frames -> frames
                .filter(f -> !f.getClassName().equals(SELF))
                .findFirst()
                .map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber())
                .orElse("unknown"));
    }
}

package org.texboobcat.tessellate.region;

// Which shards a bounding-box query has to visit.
//
// Split out from RegionShardedEntityStorage and kept free of Minecraft types so it can
// be unit tested, because this is where a mistake is invisible. Selecting one cell too few does not
// throw or corrupt anything. It silently omits entities in a shard that was never
// visited, which surfaces much later as a mob walking through another mob.
public final class RegionShardBounds {

    // The horizontal slack vanilla adds before deciding which sections a box touches.
    //
    // EntitySectionStorage.forEachAccessibleNonEmptySection expands the box by 2 blocks
    // in x and z (and 4 downwards, which does not affect shard choice since shards are 2D). Shard
    // selection has to apply the same expansion or a section inside vanilla's range would be
    // skipped because nothing ever looked in its shard.
    public static final double HORIZONTAL_MARGIN = 2.0;

    private RegionShardBounds() {
    }

    // Block coordinate to section coordinate, matching SectionPos.posToSectionCoord.
    //
    // Floor then arithmetic shift, never division: block -1 is in section -1, whereas
    // -1 / 16 would wrongly give section 0.
    public static int sectionCoord(double blockPos) {
        return (int) Math.floor(blockPos) >> 4;
    }

    // Section coordinate to region cell. Sections are 16 blocks, so a section coordinate is a
    // chunk coordinate and this is the same mapping the regionizer uses.
    public static int cellCoord(int sectionCoord, int sectionShift) {
        return sectionCoord >> sectionShift;
    }

    public static int minCell(double blockMin, int sectionShift) {
        return cellCoord(sectionCoord(blockMin - HORIZONTAL_MARGIN), sectionShift);
    }

    public static int maxCell(double blockMax, int sectionShift) {
        return cellCoord(sectionCoord(blockMax + HORIZONTAL_MARGIN), sectionShift);
    }

}

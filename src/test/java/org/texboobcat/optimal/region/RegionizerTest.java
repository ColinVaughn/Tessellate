package org.texboobcat.optimal.region;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionizerTest {

    // Records lifecycle events so tests can assert on them.
    static final class Recorder implements RegionizerListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onRegionCreated(Region region) {
            events.add("created:" + region.id());
        }

        @Override
        public void onRegionsMerged(Region survivor, List<Region> merged) {
            List<Integer> ids = new ArrayList<>();
            merged.forEach(r -> ids.add(r.id()));
            ids.sort(null);
            events.add("merged:" + survivor.id() + "<-" + ids);
        }

        @Override
        public void onRegionSplit(Region original, List<Region> splitOff) {
            List<Integer> ids = new ArrayList<>();
            splitOff.forEach(r -> ids.add(r.id()));
            ids.sort(null);
            events.add("split:" + original.id() + "->" + ids);
        }

        @Override
        public void onRegionDestroyed(Region region) {
            events.add("destroyed:" + region.id());
        }
    }

    private static Regionizer regionizer(Recorder recorder) {
        // sectionShift 2 (4x4 chunk sections), mergeRadius 2
        return new Regionizer(2, 2, recorder);
    }

    @Test
    void pendingChangesDoNotApplyUntilUpdate() {
        Recorder rec = new Recorder();
        Regionizer r = regionizer(rec);
        r.queueAddChunk(0, 0);
        assertEquals(0, r.regionCount());
        assertTrue(rec.events.isEmpty());
        r.update();
        assertEquals(1, r.regionCount());
    }

    @Test
    void firstChunkCreatesOneRegion() {
        Recorder rec = new Recorder();
        Regionizer r = regionizer(rec);
        r.queueAddChunk(0, 0);
        r.update();
        assertEquals(1, r.regionCount());
        assertEquals(List.of("created:1"), rec.events);
        assertNotNull(r.regionForChunk(0, 0));
    }

    @Test
    void chunksInTheSameSectionShareARegion() {
        Regionizer r = regionizer(new Recorder());
        r.queueAddChunk(0, 0);
        r.queueAddChunk(3, 3); // same 4x4 section
        r.update();
        assertEquals(1, r.regionCount());
        assertSame(r.regionForChunk(0, 0), r.regionForChunk(3, 3));
    }

    @Test
    void farApartChunksMakeSeparateRegions() {
        Regionizer r = regionizer(new Recorder());
        r.queueAddChunk(0, 0);
        r.queueAddChunk(64, 0); // section 16, well beyond mergeRadius 2
        r.update();
        assertEquals(2, r.regionCount());
        assertNotSame(r.regionForChunk(0, 0), r.regionForChunk(64, 0));
    }

    @Test
    void sectionsWithinMergeRadiusJoinTheSameRegion() {
        Regionizer r = regionizer(new Recorder());
        r.queueAddChunk(0, 0);   // section 0,0
        r.queueAddChunk(8, 0);   // section 2,0 -> chebyshev 2 == mergeRadius
        r.update();
        assertEquals(1, r.regionCount());
        assertSame(r.regionForChunk(0, 0), r.regionForChunk(8, 0));
    }

    @Test
    void sectionsJustBeyondMergeRadiusStaySeparate() {
        Regionizer r = regionizer(new Recorder());
        r.queueAddChunk(0, 0);    // section 0,0
        r.queueAddChunk(12, 0);   // section 3,0 -> chebyshev 3 > mergeRadius 2
        r.update();
        assertEquals(2, r.regionCount());
    }

    @Test
    void bridgingSectionMergesTwoRegions() {
        Recorder rec = new Recorder();
        Regionizer r = regionizer(rec);
        r.queueAddChunk(0, 0);    // section 0,0
        r.queueAddChunk(24, 0);   // section 6,0
        r.update();
        assertEquals(2, r.regionCount());

        rec.events.clear();
        r.queueAddChunk(12, 0);   // section 3,0 -> chebyshev 3 from both section 0 and section 6
        r.update();
        assertEquals(3, r.regionCount(), "section 3 reaches neither neighbor, so it stands alone");

        r.queueAddChunk(4, 0);    // section 1,0 -> radius 1 from section 0, radius 2 from section 3
        r.update();
        assertEquals(2, r.regionCount(), "sections 0-1-3 join; section 6 is still isolated");

        r.queueAddChunk(16, 0);   // section 4,0 -> radius 1 from section 3, radius 2 from section 6
        r.update();
        assertEquals(1, r.regionCount(), "sections 0-1-3-4-6 now form one chain");
    }

    @Test
    void mergeKeepsTheLargestRegionAsSurvivor() {
        Recorder rec = new Recorder();
        Regionizer r = regionizer(rec);
        // Region A: sections 0,0 / 1,0 / 2,0 (three sections)
        r.queueAddChunk(0, 0);
        r.queueAddChunk(4, 0);
        r.queueAddChunk(8, 0);
        // Region B: section 8,0 (one section, far away)
        r.queueAddChunk(32, 0);
        r.update();
        assertEquals(2, r.regionCount());
        Region big = r.regionForChunk(0, 0);
        Region small = r.regionForChunk(32, 0);
        assertEquals(3, big.sectionCount());
        assertEquals(1, small.sectionCount());

        rec.events.clear();
        // sections 4,0 and 6,0 bridge them
        r.queueAddChunk(16, 0);
        r.queueAddChunk(24, 0);
        r.update();

        assertEquals(1, r.regionCount());
        Region survivor = r.regionForChunk(0, 0);
        assertSame(big, survivor, "the larger region should absorb the smaller");
        assertSame(survivor, r.regionForChunk(32, 0));
        assertTrue(rec.events.stream().anyMatch(e -> e.startsWith("merged:")));
        assertTrue(rec.events.contains("destroyed:" + small.id()));
    }

    @Test
    void allSectionsMapBackToTheirRegion() {
        Regionizer r = regionizer(new Recorder());
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                r.queueAddChunk(x, z);
            }
        }
        r.update();
        assertEquals(1, r.regionCount());
        Region only = r.regions().iterator().next();
        for (long section : only.sections()) {
            assertSame(only, r.regionForSection(section));
        }
    }
}

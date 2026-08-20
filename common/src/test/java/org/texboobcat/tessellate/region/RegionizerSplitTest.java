package org.texboobcat.tessellate.region;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionizerSplitTest {

    static final class Recorder implements RegionizerListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onRegionCreated(Region region) {
            events.add("created:" + region.id());
        }

        @Override
        public void onRegionSplit(Region original, List<Region> splitOff) {
            events.add("split:" + original.id() + "->" + splitOff.size());
        }

        @Override
        public void onRegionDestroyed(Region region) {
            events.add("destroyed:" + region.id());
        }
    }

    private static Regionizer regionizer(RegionizerListener listener) {
        // mergeRadius 1 keeps the geometry easy to reason about
        return new Regionizer(2, 1, listener);
    }

    @Test
    void sectionStaysWhileAnyOfItsChunksTick() {
        Regionizer r = regionizer(RegionizerListener.NOOP);
        r.queueAddChunk(0, 0);
        r.queueAddChunk(1, 0); // same section
        r.update();
        assertEquals(1, r.regionCount());

        r.queueRemoveChunk(0, 0);
        r.update();
        assertEquals(1, r.regionCount(), "section still has a ticking chunk");

        r.queueRemoveChunk(1, 0);
        r.update();
        assertEquals(0, r.regionCount(), "last chunk gone, region destroyed");
    }

    @Test
    void removingTheOnlySectionDestroysTheRegion() {
        Recorder rec = new Recorder();
        Regionizer r = regionizer(rec);
        r.queueAddChunk(0, 0);
        r.update();
        int id = r.regionForChunk(0, 0).id();

        rec.events.clear();
        r.queueRemoveChunk(0, 0);
        r.update();

        assertEquals(0, r.regionCount());
        assertEquals(List.of("destroyed:" + id), rec.events);
        assertNull(r.regionForChunk(0, 0));
    }

    @Test
    void removingAMiddleSectionSplitsTheRegion() {
        Recorder rec = new Recorder();
        Regionizer r = regionizer(rec);
        // A line of three sections: 0,0 - 1,0 - 2,0 (chunks 0, 4, 8 at shift 2)
        r.queueAddChunk(0, 0);
        r.queueAddChunk(4, 0);
        r.queueAddChunk(8, 0);
        r.update();
        assertEquals(1, r.regionCount());

        rec.events.clear();
        r.queueRemoveChunk(4, 0); // remove the middle
        r.update();

        assertEquals(2, r.regionCount());
        assertNotSame(r.regionForChunk(0, 0), r.regionForChunk(8, 0));
        assertNull(r.regionForChunk(4, 0));
        assertEquals(1, r.splitCount());
        assertTrue(rec.events.stream().anyMatch(e -> e.startsWith("split:")));
        assertTrue(rec.events.stream().anyMatch(e -> e.startsWith("created:")));
    }

    @Test
    void removingAnEdgeSectionDoesNotSplit() {
        Regionizer r = regionizer(RegionizerListener.NOOP);
        r.queueAddChunk(0, 0);
        r.queueAddChunk(4, 0);
        r.queueAddChunk(8, 0);
        r.update();

        r.queueRemoveChunk(8, 0);
        r.update();

        assertEquals(1, r.regionCount());
        assertEquals(0, r.splitCount());
    }

    @Test
    void splitKeepsTheLargestComponentInTheOriginalRegion() {
        Regionizer r = regionizer(RegionizerListener.NOOP);
        // sections 0..4 on a line; removing section 1 leaves {0} and {2,3,4}
        for (int i = 0; i <= 4; i++) {
            r.queueAddChunk(i * 4, 0);
        }
        r.update();
        Region original = r.regionForChunk(0, 0);
        int originalId = original.id();

        r.queueRemoveChunk(4, 0); // section 1
        r.update();

        assertEquals(2, r.regionCount());
        Region big = r.regionForChunk(8, 0);
        Region small = r.regionForChunk(0, 0);
        assertEquals(3, big.sectionCount());
        assertEquals(1, small.sectionCount());
        assertEquals(originalId, big.id(), "the larger component keeps the original id");
    }

    @Test
    void splitIntoThreeComponents() {
        Regionizer r = regionizer(RegionizerListener.NOOP);
        // sections 0,1,2,3,4 -> remove 1 and 3 -> {0} {2} {4}
        for (int i = 0; i <= 4; i++) {
            r.queueAddChunk(i * 4, 0);
        }
        r.update();

        r.queueRemoveChunk(4, 0);
        r.queueRemoveChunk(12, 0);
        r.update();

        assertEquals(3, r.regionCount());
        assertNotSame(r.regionForChunk(0, 0), r.regionForChunk(8, 0));
        assertNotSame(r.regionForChunk(8, 0), r.regionForChunk(16, 0));
    }

    @Test
    void addAndRemoveInTheSameBatchCancelOut() {
        Regionizer r = regionizer(RegionizerListener.NOOP);
        r.queueAddChunk(0, 0);
        r.queueRemoveChunk(0, 0);
        r.update();
        assertEquals(0, r.regionCount());
        assertFalse(r.hasPendingChanges());
    }

    @Test
    void everySectionMapsToARegionAfterChurn() {
        Regionizer r = regionizer(RegionizerListener.NOOP);
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                r.queueAddChunk(x, z);
            }
        }
        r.update();
        for (int x = 4; x < 8; x++) {
            for (int z = 0; z < 12; z++) {
                r.queueRemoveChunk(x, z);
            }
        }
        r.update();

        int counted = 0;
        for (Region region : r.regions()) {
            counted += region.sectionCount();
            for (long section : region.sections()) {
                assertSame(region, r.regionForSection(section),
                    "section " + RegionSectionPos.toString(section) + " maps to the wrong region");
            }
        }
        assertTrue(counted > 0);
    }
}

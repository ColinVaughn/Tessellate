package org.texboobcat.tessellate.region;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainThreadBoundariesTest {

    @BeforeEach
    void reset() {
        MainThreadBoundaries.reset();
    }

    @Test
    void queuedAndDirectBoundaryWorkRemainExactlyAccounted() {
        var boundary = MainThreadBoundaries.Boundary.CROSS_REGION_WRITES;
        String source = MainThreadBoundaries.source("minecraft:overworld", 7);

        MainThreadBoundaries.queued(boundary, source);
        MainThreadBoundaries.replay(boundary, source,
            () -> MainThreadBoundaries.measure(boundary, source, () -> { }));
        MainThreadBoundaries.measure(boundary, "global/main", () -> { });
        MainThreadBoundaries.queued(boundary, source);
        assertThrows(IllegalStateException.class, () ->
            MainThreadBoundaries.replay(boundary, source,
                () -> { throw new IllegalStateException("queued failure"); }));
        assertThrows(IllegalStateException.class, () ->
            MainThreadBoundaries.measure(boundary, "global/main",
                () -> { throw new IllegalStateException("direct failure"); }));

        MainThreadBoundaries.Snapshot snapshot =
            MainThreadBoundaries.snapshot(boundary);
        assertEquals(2, snapshot.queued());
        assertEquals(1, snapshot.replayed());
        assertEquals(1, snapshot.directCalls());
        assertEquals(0, snapshot.pending());
        assertEquals(1, snapshot.queuedFailures());
        assertEquals(1, snapshot.directFailures());
        assertTrue(snapshot.balanced());
        assertEquals("global/main", snapshot.lastSource());
    }
}

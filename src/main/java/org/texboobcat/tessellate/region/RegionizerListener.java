package org.texboobcat.tessellate.region;

import java.util.List;

// Lifecycle callbacks fired by Regionizer.update() on the main thread, never from a region
// worker. These callbacks move per-region tick state between regions and support
// diagnostics and tests.
public interface RegionizerListener {

    RegionizerListener NOOP = new RegionizerListener() {
    };

    // A region came into existence with at least one section.
    default void onRegionCreated(Region region) {
    }

    // merged have been absorbed into survivor and are now dead. The survivor already
    // owns every section that belonged to them.
    default void onRegionsMerged(Region survivor, List<Region> merged) {
    }

    // original lost connectivity and was divided. original retains the largest
    // component; splitOff are newly created and already own their sections.
    default void onRegionSplit(Region original, List<Region> splitOff) {
    }

    // A region lost its last section and is now dead.
    default void onRegionDestroyed(Region region) {
    }
}

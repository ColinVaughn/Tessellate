package org.texboobcat.tessellate.guard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// Records ownership violations, collapsing repeats from the same call site.
//
// A single bad mod handler can violate thousands of times per tick, so the log counts every
// occurrence but stores one entry per distinct site. Thread-safe: violations originate on region
// worker threads.
public final class ViolationLog {

    // One distinct violating call site.
    public static final class Entry {
        private final int regionId;
        private final String levelKey;
        private final int chunkX;
        private final int chunkZ;
        private final Ownership.Mode mode;
        private final String site;
        private final AtomicLong count = new AtomicLong();

        Entry(int regionId, String levelKey, int chunkX, int chunkZ, Ownership.Mode mode, String site) {
            this.regionId = regionId;
            this.levelKey = levelKey;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.mode = mode;
            this.site = site;
        }

        public int regionId() {
            return this.regionId;
        }

        public String levelKey() {
            return this.levelKey;
        }

        public int chunkX() {
            return this.chunkX;
        }

        public int chunkZ() {
            return this.chunkZ;
        }

        public Ownership.Mode mode() {
            return this.mode;
        }

        public String site() {
            return this.site;
        }

        public long count() {
            return this.count.get();
        }

        void increment() {
            this.count.incrementAndGet();
        }
    }

    private final Map<String, Entry> bySite = new ConcurrentHashMap<>();
    private final AtomicLong total = new AtomicLong();

    public void record(int regionId, String levelKey, int chunkX, int chunkZ, Ownership.Mode mode, String site) {
        this.total.incrementAndGet();
        this.bySite
            .computeIfAbsent(site, key -> new Entry(regionId, levelKey, chunkX, chunkZ, mode, key))
            .increment();
    }

    public long total() {
        return this.total.get();
    }

    public int distinctSites() {
        return this.bySite.size();
    }

    // Distinct sites, most frequent first.
    public List<Entry> entries() {
        List<Entry> list = new ArrayList<>(this.bySite.values());
        list.sort((a, b) -> Long.compare(b.count(), a.count()));
        return Collections.unmodifiableList(list);
    }

    public void clear() {
        this.bySite.clear();
        this.total.set(0);
    }
}

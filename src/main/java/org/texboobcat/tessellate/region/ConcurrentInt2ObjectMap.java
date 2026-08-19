package org.texboobcat.tessellate.region;

import it.unimi.dsi.fastutil.objects.AbstractObjectCollection;
import it.unimi.dsi.fastutil.objects.AbstractObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectIterators;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.ints.AbstractInt2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// An Int2ObjectMap that tolerates concurrent writes and iteration.
//
// fastutil ships no concurrent primitive map, and its synchronized wrapper is not enough here:
// it makes each call atomic but still requires the caller to hold the lock across an iteration,
// which vanilla does not do when it walks the level's entity list.
//
// Backed by ConcurrentHashMap, so keys are boxed. This is installed only when the
// entity-storage split is enabled; disabling the split keeps vanilla's faster
// Int2ObjectLinkedOpenHashMap. The boxed operations are entity add and remove and lookup by
// network id, none of which run per entity per tick.
//
// Iteration order is not insertion order. Vanilla's map is linked, so
// getAllEntities() yields entities in the order they were added; this one yields them in
// hash order. The tick loop only counts or sorts the results. A caller that takes the first match
// of an unsorted query can pick a different
// entity than vanilla would.
public final class ConcurrentInt2ObjectMap<V> extends AbstractInt2ObjectMap<V> {

    private final ConcurrentHashMap<Integer, V> backing = new ConcurrentHashMap<>();

    @Override
    public V get(int key) {
        V value = this.backing.get(key);
        return value == null ? this.defRetValue : value;
    }

    @Override
    public V put(int key, V value) {
        V previous = this.backing.put(key, value);
        return previous == null ? this.defRetValue : previous;
    }

    @Override
    public V remove(int key) {
        V previous = this.backing.remove(key);
        return previous == null ? this.defRetValue : previous;
    }

    @Override
    public boolean containsKey(int key) {
        return this.backing.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return this.backing.containsValue(value);
    }

    @Override
    public int size() {
        return this.backing.size();
    }

    @Override
    public boolean isEmpty() {
        return this.backing.isEmpty();
    }

    @Override
    public void clear() {
        this.backing.clear();
    }

    // Overridden rather than derived from the entry set, which would allocate one entry object per
    // element. This is the collection getAllEntities() returns, walked once per tick by
    // mob-cap counting, so deriving it would add garbage proportional to the entity count.
    @Override
    public ObjectCollection<V> values() {
        return new AbstractObjectCollection<>() {
            @Override
            public ObjectIterator<V> iterator() {
                return ObjectIterators.asObjectIterator(backing.values().iterator());
            }

            @Override
            public int size() {
                return backing.size();
            }
        };
    }

    @Override
    public ObjectSet<Int2ObjectMap.Entry<V>> int2ObjectEntrySet() {
        return new AbstractObjectSet<>() {
            @Override
            public ObjectIterator<Int2ObjectMap.Entry<V>> iterator() {
                Iterator<Map.Entry<Integer, V>> entries = backing.entrySet().iterator();
                return new ObjectIterator<>() {
                    @Override
                    public boolean hasNext() {
                        return entries.hasNext();
                    }

                    @Override
                    public Int2ObjectMap.Entry<V> next() {
                        Map.Entry<Integer, V> entry = entries.next();
                        return new BasicEntry<>(entry.getKey().intValue(), entry.getValue());
                    }
                };
            }

            @Override
            public int size() {
                return backing.size();
            }
        };
    }
}

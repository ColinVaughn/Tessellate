package org.texboobcat.tessellate.region;

import java.util.concurrent.atomic.AtomicInteger;

// Vanilla's block-position LCG, with one mutable state per calling thread.
public final class ThreadLocalBlockRandom {

    private static final int ADDEND = 1013904223;
    private static final int SEED_STEP = 0x9E3779B9;

    private final ThreadLocal<int[]> state;

    public ThreadLocalBlockRandom(int initialSeed) {
        AtomicInteger nextSeed = new AtomicInteger(initialSeed + SEED_STEP);
        this.state = ThreadLocal.withInitial(() -> new int[]{nextSeed.getAndAdd(SEED_STEP)});
        this.state.set(new int[]{initialSeed});
    }

    public int nextBits() {
        return nextState() >> 2;
    }

    int nextState() {
        int[] current = this.state.get();
        current[0] = advance(current[0]);
        return current[0];
    }

    static int advance(int state) {
        return state * 3 + ADDEND;
    }
}

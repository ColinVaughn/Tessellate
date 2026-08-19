package org.texboobcat.optimal.region;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

// A RandomSource that gives every thread its own generator.
//
// Level.random is a LegacyRandomSource, which is not thread-safe: it is a plain
// mutable seed with no synchronisation, so two threads drawing from it concurrently corrupt each
// other's sequences and can return values outside the documented range. Region workers all draw
// from it constantly for mob AI, item movement, and block ticks, so it must be split before any of
// them run in parallel.
//
// Vanilla's own RandomSource.createThreadSafe() would also be correct, but it
// synchronises on a shared AtomicLong, so every draw from every region contends on one
// cache line. A per-thread generator is both correct and uncontended.
//
// The cost is that the sequence of values is no longer reproducible from a single seed, since
// it now depends on which thread drew which value. Vanilla's sequence was already not reproducible
// in practice. Level.random is seeded from RandomSupport.generateUniqueSeed() at
// construction, so nothing reproducible is lost.
public final class ThreadLocalRandomSource implements RandomSource {

    public interface BlockRandomAccess {
        int optimal$nextBlockRandomBits();
    }

    private final ThreadLocal<RandomSource> delegate =
        ThreadLocal.withInitial(RandomSource::createNewThreadLocalInstance);

    private RandomSource get() {
        return this.delegate.get();
    }

    @Override
    public RandomSource fork() {
        return get().fork();
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return get().forkPositional();
    }

    // Seeds only the calling thread's generator.
    //
    // Vanilla callers use this to make a specific draw reproducible and then immediately draw
    // from the same thread, so confining it to the caller preserves that. It cannot mean anything
    // stronger here: reseeding every thread's generator from one thread would be a data race, which
    // is the thing this class exists to remove.
    @Override
    public void setSeed(long seed) {
        get().setSeed(seed);
    }

    @Override
    public int nextInt() {
        return get().nextInt();
    }

    @Override
    public int nextInt(int bound) {
        return get().nextInt(bound);
    }

    @Override
    public long nextLong() {
        return get().nextLong();
    }

    @Override
    public boolean nextBoolean() {
        return get().nextBoolean();
    }

    @Override
    public float nextFloat() {
        return get().nextFloat();
    }

    @Override
    public double nextDouble() {
        return get().nextDouble();
    }

    @Override
    public double nextGaussian() {
        return get().nextGaussian();
    }
}

package org.texboobcat.optimal.region;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongFunction;

// One level-wide natural-spawn snapshot plus atomic cap/potential reservations.
//
// A Session is bound only while vanilla's spawnForChunk runs. Mixins route SpawnState's three
// mutable callbacks here, leaving the rest of vanilla and NeoForge's spawn hooks unchanged.
public final class ParallelNaturalSpawner {

    public record Stats(long snapshots, long reserved, long committed, long rolledBack,
                        long rejected, long failures, int outstanding, int peakOutstanding,
                        long capViolations) {
    }

    private record PlayerSnapshot(double x, double z) {
    }

    private static final class Charge {
        private final BlockPos pos;
        private final double value;

        private Charge(BlockPos pos, double value) {
            this.pos = pos;
            this.value = value;
        }

        private double potentialAt(BlockPos other) {
            double distance = this.pos.distSqr(other);
            return distance == 0.0 ? Double.POSITIVE_INFINITY
                : this.value / Math.sqrt(distance);
        }
    }

    private record Candidate(EntityType<?> type, BlockPos pos, double charge,
                             double energyBudget) {
    }

    private static final class Pending implements AutoCloseable {
        private final State state;
        private final Mob mob;
        private final BlockPos pos;
        private final NaturalSpawnReservations.Reservation caps;
        private boolean committed;

        private Pending(State state, Mob mob, BlockPos pos,
                        NaturalSpawnReservations.Reservation caps) {
            this.state = state;
            this.mob = mob;
            this.pos = pos;
            this.caps = caps;
        }

        private void commit(Mob spawned) {
            if (spawned != this.mob || !spawned.blockPosition().equals(this.pos)) {
                throw new IllegalStateException(
                    "a natural-spawn hook moved or replaced the reserved mob");
            }
            this.caps.commit();
            this.committed = true;
            COMMITTED.increment();
            OUTSTANDING.decrementAndGet();
            this.state.assertCaps();
        }

        @Override
        public void close() {
            if (this.committed) {
                return;
            }
            this.caps.close();
            ROLLED_BACK.increment();
            OUTSTANDING.decrementAndGet();
        }
    }

    public static final class State {
        private final LongFunction<LevelChunk> chunks;
        private final List<PlayerSnapshot> players;
        private final List<Charge> charges = new ArrayList<>();
        private final NaturalSpawnReservations caps;
        private final int[] globalLimits;
        private final int[] localLimits;
        private final int[] globalCeilings;

        public State(ServerLevel level, int spawnableChunkCount,
                     Object2IntMap<MobCategory> globalCounts, Iterable<Entity> entities,
                     LongFunction<LevelChunk> chunks) {
            this.chunks = chunks;
            this.players = level.players().stream()
                .filter(player -> !player.isSpectator())
                .map(player -> new PlayerSnapshot(player.getX(), player.getZ()))
                .toList();

            MobCategory[] categories = MobCategory.values();
            int[] initialGlobal = new int[categories.length];
            this.globalLimits = new int[categories.length];
            this.localLimits = new int[categories.length];
            int[][] initialLocal = new int[this.players.size()][categories.length];
            initializeLimits(categories, globalCounts, spawnableChunkCount, initialGlobal);
            collectEntitySnapshots(entities, initialLocal);
            this.globalCeilings = ceilings(initialGlobal, this.globalLimits);
            this.caps = new NaturalSpawnReservations(initialGlobal, this.globalLimits,
                initialLocal, this.localLimits);
            SNAPSHOTS.increment();
            assertCaps();
        }

        private void initializeLimits(MobCategory[] categories,
                Object2IntMap<MobCategory> globalCounts, int spawnableChunkCount,
                int[] initialGlobal) {
            for (MobCategory category : categories) {
                int ordinal = category.ordinal();
                initialGlobal[ordinal] = globalCounts.getInt(category);
                this.globalLimits[ordinal] = category.getMaxInstancesPerChunk()
                    * spawnableChunkCount / 289;
                this.localLimits[ordinal] = category.getMaxInstancesPerChunk();
            }
        }

        private void collectEntitySnapshots(Iterable<Entity> entities, int[][] initialLocal) {
            for (Entity entity : entities) {
                addEntitySnapshot(entity, initialLocal);
            }
        }

        private void addEntitySnapshot(Entity entity, int[][] initialLocal) {
            if (isPersistentMob(entity)) {
                return;
            }
            MobCategory category = entity.getClassification(true);
            if (category == MobCategory.MISC) {
                return;
            }
            BlockPos pos = entity.blockPosition();
            LevelChunk chunk = this.chunks.apply(ChunkPos.asLong(pos));
            if (chunk == null) {
                return;
            }
            addCharge(entity, pos, chunk);
            if (entity instanceof Mob) {
                addLocalMob(category, pos, initialLocal);
            }
        }

        private static boolean isPersistentMob(Entity entity) {
            return entity instanceof Mob mob
                && (mob.isPersistenceRequired() || mob.requiresCustomPersistence());
        }

        private void addCharge(Entity entity, BlockPos pos, LevelChunk chunk) {
            MobSpawnSettings.MobSpawnCost cost = cost(entity.getType(), pos, chunk);
            if (cost != null && cost.charge() != 0.0) {
                this.charges.add(new Charge(pos.immutable(), cost.charge()));
            }
        }

        private void addLocalMob(MobCategory category, BlockPos pos, int[][] initialLocal) {
            for (int player : playersNear(new ChunkPos(pos))) {
                initialLocal[player][category.ordinal()]++;
            }
        }

        private static int[] ceilings(int[] initial, int[] limits) {
            int[] ceilings = new int[initial.length];
            for (int category = 0; category < initial.length; category++) {
                ceilings[category] = Math.max(initial[category], limits[category]);
            }
            return ceilings;
        }

        private boolean canSpawnForCategory(MobCategory category, ChunkPos chunk) {
            return this.caps.canReserve(category.ordinal(), playersNear(chunk));
        }

        private boolean canSpawn(EntityType<?> type, BlockPos pos, ChunkAccess chunk) {
            MobSpawnSettings.MobSpawnCost cost = cost(type, pos, chunk);
            Session session = session();
            session.candidate = new Candidate(type, pos.immutable(),
                cost == null ? 0.0 : cost.charge(),
                cost == null ? Double.POSITIVE_INFINITY : cost.energyBudget());
            synchronized (this.caps) {
                return potentialAllows(session.candidate);
            }
        }

        private boolean reserve(Mob mob) {
            Session session = session();
            Candidate candidate = session.candidate;
            BlockPos pos = mob.blockPosition();
            if (candidate == null || candidate.type() != mob.getType()
                || !candidate.pos().equals(pos)) {
                LevelChunk chunk = this.chunks.apply(ChunkPos.asLong(pos));
                if (chunk == null) {
                    throw new IllegalStateException("natural spawn moved into an unloaded chunk");
                }
                MobSpawnSettings.MobSpawnCost cost = cost(mob.getType(), pos, chunk);
                candidate = new Candidate(mob.getType(), pos.immutable(),
                    cost == null ? 0.0 : cost.charge(),
                    cost == null ? Double.POSITIVE_INFINITY : cost.energyBudget());
            }

            int category = mob.getType().getCategory().ordinal();
            int[] nearby = playersNear(new ChunkPos(pos));
            Candidate admitted = candidate;
            Charge charge = new Charge(pos.immutable(), candidate.charge());
            NaturalSpawnReservations.Reservation reservation = this.caps.tryReserve(
                category, nearby, () -> potentialAllows(admitted),
                () -> {
                    if (charge.value != 0.0) {
                        this.charges.add(charge);
                    }
                },
                () -> this.charges.remove(charge));
            if (reservation == null) {
                REJECTED.increment();
                return false;
            }
            session.pending = new Pending(this, mob, pos.immutable(), reservation);
            session.candidate = null;
            RESERVED.increment();
            int outstanding = OUTSTANDING.incrementAndGet();
            PEAK_OUTSTANDING.accumulateAndGet(outstanding, Math::max);
            assertCaps();
            return true;
        }

        private boolean potentialAllows(Candidate candidate) {
            if (candidate.charge() == 0.0) {
                return true;
            }
            double potential = 0.0;
            for (Charge charge : this.charges) {
                potential += charge.potentialAt(candidate.pos());
            }
            return potential * candidate.charge() <= candidate.energyBudget();
        }

        private int[] playersNear(ChunkPos chunk) {
            double centerX = chunk.getMinBlockX() + 8.0;
            double centerZ = chunk.getMinBlockZ() + 8.0;
            int[] scratch = new int[this.players.size()];
            int count = 0;
            for (int i = 0; i < this.players.size(); i++) {
                PlayerSnapshot player = this.players.get(i);
                double dx = centerX - player.x();
                double dz = centerZ - player.z();
                if (dx * dx + dz * dz < 16384.0) {
                    scratch[count++] = i;
                }
            }
            return java.util.Arrays.copyOf(scratch, count);
        }

        @Nullable
        private static MobSpawnSettings.MobSpawnCost cost(EntityType<?> type, BlockPos pos,
                                                           ChunkAccess chunk) {
            return chunk.getNoiseBiome(QuartPos.fromBlock(pos.getX()),
                    QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ()))
                .value().getMobSettings().getMobSpawnCost(type);
        }

        private void assertCaps() {
            for (MobCategory category : MobCategory.values()) {
                int ordinal = category.ordinal();
                if (this.caps.globalCount(ordinal) > this.globalCeilings[ordinal]) {
                    CAP_VIOLATIONS.increment();
                    throw new IllegalStateException("global natural-spawn cap exceeded for "
                        + category);
                }
            }
        }
    }

    private static final class Session implements AutoCloseable {
        private final State state;
        private final List<Runnable> entityAdds = new ArrayList<>();
        @Nullable
        private Candidate candidate;
        @Nullable
        private Pending pending;

        private Session(State state) {
            this.state = state;
        }

        private void captureEntityAdd(Runnable add) {
            if (this.pending == null) {
                throw new IllegalStateException(
                    "natural spawning tried to add an entity without a cap reservation");
            }
            this.entityAdds.add(add);
        }

        private void afterSpawn(Mob mob, Runnable vanillaStateUpdate) {
            if (this.pending == null) {
                throw new IllegalStateException("natural spawn completed without a reservation");
            }
            this.pending.commit(mob);
            this.pending = null;
            for (Runnable add : this.entityAdds) {
                DeferredMainThreadWork.defer(
                    MainThreadBoundaries.Boundary.ENTITY_LIFECYCLE, add);
            }
            DeferredMainThreadWork.defer(
                MainThreadBoundaries.Boundary.NATURAL_SPAWN_COMMITS, vanillaStateUpdate);
            this.entityAdds.clear();
        }

        @Override
        public void close() {
            if (this.pending != null) {
                this.pending.close();
                this.pending = null;
            }
            this.entityAdds.clear();
        }
    }

    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
    private static final LongAdder SNAPSHOTS = new LongAdder();
    private static final LongAdder RESERVED = new LongAdder();
    private static final LongAdder COMMITTED = new LongAdder();
    private static final LongAdder ROLLED_BACK = new LongAdder();
    private static final LongAdder REJECTED = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final LongAdder CAP_VIOLATIONS = new LongAdder();
    private static final AtomicInteger OUTSTANDING = new AtomicInteger();
    private static final AtomicInteger PEAK_OUTSTANDING = new AtomicInteger();
    private static volatile boolean parallelAllowed = true;
    @Nullable
    private static volatile String degradeReason;

    private ParallelNaturalSpawner() {
    }

    public static void run(State state, Runnable spawn) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("nested natural-spawn session");
        }
        try (Session ignored = new Session(state)) {
            CURRENT.set(ignored);
            spawn.run();
        } finally {
            CURRENT.remove();
        }
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    public static boolean canSpawnForCategory(MobCategory category, ChunkPos chunk) {
        return session().state.canSpawnForCategory(category, chunk);
    }

    public static boolean canSpawn(EntityType<?> type, BlockPos pos, ChunkAccess chunk) {
        return session().state.canSpawn(type, pos, chunk);
    }

    public static boolean reserve(Mob mob) {
        return session().state.reserve(mob);
    }

    public static void afterSpawn(Mob mob, Runnable vanillaStateUpdate) {
        session().afterSpawn(mob, vanillaStateUpdate);
    }

    public static boolean captureEntityAdd(Runnable add) {
        Session session = CURRENT.get();
        if (session == null) {
            return false;
        }
        session.captureEntityAdd(add);
        return true;
    }

    public static void failed() {
        FAILURES.increment();
    }

    public static boolean parallelAllowed() {
        return parallelAllowed;
    }

    @Nullable
    public static String degradeReason() {
        return degradeReason;
    }

    public static void degradeToSerial(String reason) {
        if (parallelAllowed) {
            parallelAllowed = false;
            degradeReason = reason;
        }
    }

    public static Stats stats() {
        return new Stats(SNAPSHOTS.sum(), RESERVED.sum(), COMMITTED.sum(),
            ROLLED_BACK.sum(), REJECTED.sum(), FAILURES.sum(), OUTSTANDING.get(),
            PEAK_OUTSTANDING.get(), CAP_VIOLATIONS.sum());
    }

    public static void reset() {
        SNAPSHOTS.reset();
        RESERVED.reset();
        COMMITTED.reset();
        ROLLED_BACK.reset();
        REJECTED.reset();
        FAILURES.reset();
        CAP_VIOLATIONS.reset();
        OUTSTANDING.set(0);
        PEAK_OUTSTANDING.set(0);
        parallelAllowed = true;
        degradeReason = null;
        CURRENT.remove();
    }

    private static Session session() {
        Session session = CURRENT.get();
        if (session == null) {
            throw new IllegalStateException("no natural-spawn session is active");
        }
        return session;
    }
}

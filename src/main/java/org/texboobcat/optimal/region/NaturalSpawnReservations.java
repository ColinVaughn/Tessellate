package org.texboobcat.optimal.region;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

// Atomic global/local mob-cap reservations. Kept free of Minecraft types so the race-prone
// check-and-increment rule has a small deterministic unit test.
final class NaturalSpawnReservations {

    final class Reservation implements AutoCloseable {
        private final int category;
        private final int[] players;
        private final Runnable rollback;
        private boolean finished;

        private Reservation(int category, int[] players, Runnable rollback) {
            this.category = category;
            this.players = players;
            this.rollback = rollback;
        }

        void commit() {
            synchronized (NaturalSpawnReservations.this) {
                this.finished = true;
            }
        }

        @Override
        public void close() {
            synchronized (NaturalSpawnReservations.this) {
                if (this.finished) {
                    return;
                }
                this.finished = true;
                globalCounts[this.category]--;
                for (int player : this.players) {
                    localCounts[player][this.category]--;
                }
                this.rollback.run();
            }
        }
    }

    private final int[] globalCounts;
    private final int[] globalLimits;
    private final int[][] localCounts;
    private final int[] localLimits;

    NaturalSpawnReservations(int[] globalCounts, int[] globalLimits, int[][] localCounts,
                             int[] localLimits) {
        this.globalCounts = Arrays.copyOf(globalCounts, globalCounts.length);
        this.globalLimits = Arrays.copyOf(globalLimits, globalLimits.length);
        this.localCounts = Arrays.stream(localCounts).map(int[]::clone).toArray(int[][]::new);
        this.localLimits = Arrays.copyOf(localLimits, localLimits.length);
    }

    synchronized boolean canReserve(int category, int[] players) {
        if (this.globalCounts[category] >= this.globalLimits[category]) {
            return false;
        }
        for (int player : players) {
            if (this.localCounts[player][category] < this.localLimits[category]) {
                return true;
            }
        }
        return false;
    }

    synchronized Reservation tryReserve(int category, int[] players,
                                        BooleanSupplier extraAdmission,
                                        Runnable extraReservation, Runnable extraRollback) {
        if (!canReserve(category, players) || !extraAdmission.getAsBoolean()) {
            return null;
        }
        this.globalCounts[category]++;
        for (int player : players) {
            this.localCounts[player][category]++;
        }
        try {
            extraReservation.run();
        } catch (Throwable failure) {
            this.globalCounts[category]--;
            for (int player : players) {
                this.localCounts[player][category]--;
            }
            throw failure;
        }
        return new Reservation(category, Arrays.copyOf(players, players.length), extraRollback);
    }

    synchronized int globalCount(int category) {
        return this.globalCounts[category];
    }

    synchronized int localCount(int player, int category) {
        return this.localCounts[player][category];
    }
}

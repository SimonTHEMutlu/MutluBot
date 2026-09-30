package engine;

/** UCI clock allocation in milliseconds. Kept separate from parsing for direct tests. */
final class ClockAllocation {
    private static final int DEFAULT_MOVES_TO_GO = 30;
    private static final double INCREMENT_WEIGHT = 0.8;
    private static final double OPENING_MULTIPLIER = 1.35;
    private static final double EARLY_GAME_MULTIPLIER = 1.15;
    private static final double HARD_BUDGET_MULTIPLIER = 1.5;

    static final class Budget {
        final long softMs;
        final long hardMs;

        Budget(long softMs, long hardMs) {
            this.softMs = softMs;
            this.hardMs = hardMs;
        }
    }

    private ClockAllocation() { }

    static Budget forMoveTime(long moveTimeMs) {
        if (moveTimeMs < 0) return new Budget(-1, -1);
        // Leave a few milliseconds for protocol/UI overhead while allowing the
        // search to use essentially all of the requested movetime.
        long hard = Math.max(1, moveTimeMs - Math.min(5, moveTimeMs / 10));
        return new Budget(hard, hard);
    }

    static Budget forClock(int fullmoveNumber, long myTimeMs, long myIncrementMs,
                           int movesToGo) {
        if (myTimeMs < 0) myTimeMs = 5000;
        long increment = Math.max(0, myIncrementMs);
        int mtg = movesToGo > 0 ? movesToGo : DEFAULT_MOVES_TO_GO;

        double base = (double) myTimeMs / mtg + increment * INCREMENT_WEIGHT;
        double phaseMultiplier = fullmoveNumber <= 10 ? OPENING_MULTIPLIER
                : fullmoveNumber <= 20 ? EARLY_GAME_MULTIPLIER : 1.0;
        long soft = toMillis(base * phaseMultiplier);

        // Reserve up to one percent (capped at one second) for GUI/protocol
        // latency. Scale it down for low clocks so the hard cap remains usable.
        long reserve = Math.min(1000, Math.max(1, myTimeMs / 100));
        long available = Math.max(1, myTimeMs - reserve);
        long hard = Math.min(available, toMillis(soft * HARD_BUDGET_MULTIPLIER));
        soft = Math.max(1, Math.min(soft, hard));
        return new Budget(soft, hard);
    }

    private static long toMillis(double value) {
        if (Double.isNaN(value) || value <= 0) return 1;
        if (value >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(1, (long) Math.ceil(value));
    }
}

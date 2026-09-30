package engine;

/** Focused UCI clock allocation checks (all values are milliseconds). */
public final class ClockAllocationTest {
    public static void main(String[] args) {
        testThirtyMinutesPlusThreeSeconds();
        testOpeningAndLaterGameBudgets();
        testMoveTimeUsesRequestedBudget();
        testLowAndInvalidClockInputs();
        testHardDeadlineReturnsLegalMove();
        testSoftAndHardIterationPolicy();
        System.out.println("ClockAllocationTest passed");
    }

    private static void testThirtyMinutesPlusThreeSeconds() {
        ClockAllocation.Budget budget = ClockAllocation.forClock(1, 1_800_000, 3_000, 0);
        check(budget.softMs == 84_240,
                "30min+3s opening soft budget should include the early-game multiplier: " + budget.softMs);
        check(budget.hardMs == 126_360,
                "30min+3s hard budget should allow extra time for unstable positions: " + budget.hardMs);
        check(budget.hardMs < 1_799_000, "hard budget must preserve clock reserve");

        ClockAllocation.Budget blitz = ClockAllocation.forClock(1, 30_000, 3_000, 0);
        check(blitz.softMs == 4_590 && blitz.hardMs == 6_885,
                "30s+3s values must be interpreted as milliseconds: "
                        + blitz.softMs + "/" + blitz.hardMs);
    }

    private static void testOpeningAndLaterGameBudgets() {
        ClockAllocation.Budget opening = ClockAllocation.forClock(1, 300_000, 0, 30);
        ClockAllocation.Budget early = ClockAllocation.forClock(15, 300_000, 0, 30);
        ClockAllocation.Budget later = ClockAllocation.forClock(25, 300_000, 0, 30);
        check(opening.softMs > early.softMs && early.softMs > later.softMs,
                "phase multipliers should taper after the opening");

        ClockAllocation.Budget explicitMtg = ClockAllocation.forClock(25, 300_000, 0, 10);
        check(explicitMtg.softMs == 30_000,
                "explicit moves-to-go should replace the default estimate: " + explicitMtg.softMs);
    }

    private static void testMoveTimeUsesRequestedBudget() {
        ClockAllocation.Budget budget = ClockAllocation.forMoveTime(1_000);
        check(budget.softMs == 995 && budget.hardMs == 995,
                "movetime should run to its requested limit less the small reserve");
        ClockAllocation.Budget shortMoveTime = ClockAllocation.forMoveTime(3);
        check(shortMoveTime.softMs == 3 && shortMoveTime.hardMs == 3,
                "very short movetime must remain positive and not underflow");
    }

    private static void testLowAndInvalidClockInputs() {
        ClockAllocation.Budget low = ClockAllocation.forClock(40, 5, 0, 30);
        check(low.softMs >= 1 && low.hardMs >= low.softMs && low.hardMs <= 5,
                "low clock budget must remain positive and below remaining time");

        ClockAllocation.Budget missing = ClockAllocation.forClock(30, -1, -500, 0);
        check(missing.softMs > 0 && missing.hardMs >= missing.softMs,
                "missing clocks and negative increments must be handled safely");
        ClockAllocation.Budget unlimited = ClockAllocation.forMoveTime(-1);
        check(unlimited.softMs == -1 && unlimited.hardMs == -1,
                "negative movetime should represent no time limit");
    }

    private static void testHardDeadlineReturnsLegalMove() {
        Board board = new Board();
        Search search = new Search(new TranspositionTable(1));
        long start = System.nanoTime();
        int move = search.search(board, 0, 0, 20, null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        boolean found = false;
        for (int i = 0; i < legal.size; i++) if (legal.get(i) == move) found = true;
        check(found, "hard deadline must still return a legal fallback move");
        check(elapsedMs < 1000, "immediate hard deadline must not loop through aspiration retries");
    }

    private static void testSoftAndHardIterationPolicy() {
        long second = 1_000_000_000L;
        check(SearchTimePolicy.shouldStartNextIteration(30 * second, 60 * second, 126 * second),
                "30s elapsed + 60s projected should continue with a 126s hard limit");
        check(SearchTimePolicy.shouldStopAtSoftAfterCompletedIteration(
                        90 * second, 84 * second, 126 * second, true),
                "stable completed iteration should stop after crossing the 84s soft target");
        check(!SearchTimePolicy.shouldStopAtSoftAfterCompletedIteration(
                        90 * second, 84 * second, 126 * second, false),
                "unstable search may continue beyond soft time");
        check(SearchTimePolicy.shouldStartNextIteration(90 * second, 20 * second, 126 * second),
                "unstable search may continue when the projection fits hard time");
        check(!SearchTimePolicy.shouldStartNextIteration(90 * second, 40 * second, 126 * second),
                "do not start an iteration projected to cross hard time");
        check(!SearchTimePolicy.shouldStopAtSoftAfterCompletedIteration(
                        1_000 * second, 100 * second, 100 * second, true),
                "equal soft/hard movetime limits must remain hard-deadline-driven");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

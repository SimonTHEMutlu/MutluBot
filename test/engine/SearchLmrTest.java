package engine;

import java.lang.reflect.Field;

/** Guard and reduction-depth checks for the main-search late-move reduction policy. */
public final class SearchLmrTest {
    public static void main(String[] args) {
        testLookupTableMatchesReferenceFormula();
        testRankAndDepthPolicy();
        testTacticalAndOrderingExemptions();
        testCheapEligibilityGate();
        testPvClassificationAndNonPvModifier();
        testInstrumentedSearchUsesReducedScouts();
        System.out.println("SearchLmrTest passed");
    }

    private static void testLookupTableMatchesReferenceFormula() {
        // Cover the practical search domain through MAX_PLY and beyond the
        // largest legal chess move count, then probe wider cap boundaries below.
        for (int depth = 0; depth <= Search.MAX_PLY; depth++) {
            for (int rank = 0; rank <= 256; rank++) {
                check(Search.lateMoveReduction(depth, rank)
                                == Search.lateMoveReductionFormula(depth, rank),
                        "lookup differs from formula at engine-reachable depth/rank "
                                + depth + "/" + rank);
            }
        }

        // Include negative inputs, the table caps, and broad values on both
        // sides of its saturation boundaries.
        for (int depth = -8; depth <= 256; depth++) {
            for (int rank = -8; rank <= 512; rank++) {
                check(Search.lateMoveReduction(depth, rank)
                                == Search.lateMoveReductionFormula(depth, rank),
                        "lookup differs from formula at boundary depth/rank "
                                + depth + "/" + rank);
            }
        }
        check(Search.lateMoveReduction(Integer.MIN_VALUE, 20) == 0,
                "very negative depths must be safely treated as below the LMR threshold");
        check(Search.lateMoveReduction(20, Integer.MIN_VALUE) == 0,
                "very negative move ranks must be safely treated as early moves");
        check(Search.lateMoveReduction(Integer.MAX_VALUE, Integer.MAX_VALUE) == 3,
                "indices beyond both caps must return the saturated reduction");
        check(Search.lateMoveReduction(43, 6) == 3
                        && Search.lateMoveReduction(4, 25) == 2
                        && Search.lateMoveReduction(5, 25) == 3,
                "the depth and rank caps must each reach the saturated schedule");
    }

    private static void testRankAndDepthPolicy() {
        check(Search.lateMoveReduction(2, 20) == 0, "shallow searches must not reduce");
        check(Search.lateMoveReduction(8, 4) == 0, "first four ordered moves stay nominal depth");
        check(Search.lateMoveReduction(3, 5) == 1, "fifth move at the minimum eligible depth reduces once");
        check(Search.lateMoveReduction(8, 5) == 1, "fifth quiet move begins with a one-ply scout");
        check(Search.lateMoveReduction(8, 6) == 1, "nearby quiet moves retain a conservative reduction");
        check(Search.lateMoveReduction(10, 10) == 2,
                "deeper and later moves should receive an additional reduction");
        check(Search.lateMoveReduction(16, 20) == 3,
                "only deep and late moves should receive a stronger reduction");
        check(Search.lateMoveReduction(16, 20) >= Search.lateMoveReduction(12, 20),
                "reduction should not decrease as depth increases");
        check(Search.lateMoveReduction(16, 20) >= Search.lateMoveReduction(16, 16),
                "reduction should not decrease as move rank increases");
    }

    private static void testTacticalAndOrderingExemptions() {
        Board start = new Board();
        Search search = new Search(new TranspositionTable(1));
        int quiet = find(start, "e2e4");
        int deepLateReduction = search.lmrReductionForMove(quiet, 16, 20, 4,
                Move.NONE, false, false, false);
        check(deepLateReduction > 0, "ordinary late quiet move should be reducible");
        check(search.lmrReductionForMove(quiet, 16, 20, 4, Move.NONE, true, false, false) == 0,
                "all check evasions must stay at nominal depth");
        check(search.lmrReductionForMove(quiet, 16, 20, 4, Move.NONE, false, true, false) == 0,
                "quiet checking moves must stay at nominal depth");
        check(search.lmrReductionForMove(quiet, 16, 20, 4, quiet, false, false, false) == 0,
                "TT move must stay at nominal depth");

        int[][] killers = (int[][]) field(search, "killerMoves");
        killers[4][0] = quiet;
        check(search.lmrReductionForMove(quiet, 16, 20, 4, Move.NONE, false, false, false) == 0,
                "first killer must stay at nominal depth");
        killers[4][0] = Move.NONE;
        killers[4][1] = quiet;
        check(search.lmrReductionForMove(quiet, 16, 20, 4, Move.NONE, false, false, false) == 0,
                "second killer must stay at nominal depth");

        Board captureBoard = board("3rk3/5n2/8/8/8/1r6/8/3QK3 w - - 0 1");
        int capture = find(captureBoard, "d1d8");
        check(search.lmrReductionForMove(capture, 16, 20, 4, Move.NONE, false, false, false) == 0,
                "captures must stay at nominal depth");

        Board promotionBoard = board("1r2k3/P7/8/8/8/8/8/4K3 w - - 0 1");
        int promotion = findAnyPromotion(promotionBoard);
        check(search.lmrReductionForMove(promotion, 16, 20, 4, Move.NONE, false, false, false) == 0,
                "promotions must stay at nominal depth");

        Board passer = board("4k3/8/3P4/8/8/8/8/4K3 w - - 0 1");
        int push = find(passer, "d6d7");
        passer.makeMove(push);
        check(search.isAdvancedPassedPawnPush(passer, push, Piece.WHITE),
                "advanced passed-pawn pushes should be recognized");
        check(search.lmrReductionForMove(push, 16, 20, 4, Move.NONE,
                        false, false, true) == 0,
                "advanced passed-pawn pushes must stay at nominal depth");
    }

    private static void testCheapEligibilityGate() {
        Search search = new Search(new TranspositionTable(1));
        Board start = new Board();
        int quiet = find(start, "e2e4");
        check(search.lmrBaseReductionForMove(quiet, 16, 4, 4, Move.NONE,
                        false, 0) == 0,
                "first four moves must be rejected before position-dependent LMR checks");
        check(search.adjustNonPvLmrReductionForTesting(false, quiet, 16, 4,
                        search.lmrBaseReductionForMove(quiet, 16, 4, 4, Move.NONE,
                                false, 0)) == 0,
                "non-PV modifier must not introduce a reduction for one of the first four moves");
        check(search.lmrBaseReductionForMove(quiet, 2, 20, 4, Move.NONE,
                        false, 0) == 0,
                "shallow moves must be rejected before position-dependent LMR checks");
        check(search.lmrBaseReductionForMove(quiet, 16, 20, 4, quiet,
                        false, 0) == 0,
                "TT moves must be rejected before position-dependent LMR checks");
        check(search.lmrBaseReductionForMove(quiet, 16, 20, 4, Move.NONE,
                        true, 0) == 0,
                "check evasions must be rejected before position-dependent LMR checks");

        int[][] killers = (int[][]) field(search, "killerMoves");
        killers[4][0] = quiet;
        check(search.lmrBaseReductionForMove(quiet, 16, 20, 4, Move.NONE,
                        false, 0) == 0,
                "killer moves must be rejected before position-dependent LMR checks");
        killers[4][0] = Move.NONE;

        Board captureBoard = board("3rk3/5n2/8/8/8/1r6/8/3QK3 w - - 0 1");
        int capture = find(captureBoard, "d1d8");
        check(search.lmrBaseReductionForMove(capture, 16, 20, 4, Move.NONE,
                        false, 0) == 0,
                "protected captures must be rejected before position-dependent LMR checks");
        check(search.lmrBaseReductionForMove(quiet, 16, 20, 4, Move.NONE,
                        false, 0) > 0,
                "only candidates surviving cheap guards may reach position-dependent checks");
    }

    private static void testPvClassificationAndNonPvModifier() {
        int originalAlpha = 120;
        int originalBeta = 220;
        boolean originalPvNode = Search.isPvNodeForTesting(originalAlpha, originalBeta);
        int ttUpperBound = originalAlpha + 1;
        int narrowedBeta = Math.min(originalBeta, ttUpperBound);
        check(originalPvNode && !Search.isPvNodeForTesting(originalAlpha, narrowedBeta),
                "the original PV window classification differs from one after TT narrowing");

        Search search = new Search(new TranspositionTable(1));
        Board board = new Board();
        int quiet = find(board, "e2e4");
        check(search.adjustNonPvLmrReductionForTesting(true, quiet, 8, 10, 2) == 2,
                "PV nodes must retain the base reduction");
        check(search.adjustNonPvLmrReductionForTesting(false, quiet, 5, 10, 2) == 2,
                "non-PV nodes below the depth threshold must retain the base reduction");
        check(search.adjustNonPvLmrReductionForTesting(false, quiet, 6, 7, 2) == 2,
                "non-PV nodes before move rank eight must retain the base reduction");
        check(search.adjustNonPvLmrReductionForTesting(false, quiet, 6, 8, 1) == 2,
                "eligible non-PV quiet moves at the threshold gain one reduction ply");
        check(search.adjustNonPvLmrReductionForTesting(false, quiet, 10, 8, 2) == 3,
                "the non-PV modifier adds exactly one ply to a two-ply base reduction");
        check(search.adjustNonPvLmrReductionForTesting(false, quiet, 10, 8, 3) == 4,
                "the non-PV modifier adds exactly one ply to a three-ply base reduction");
        check(search.adjustNonPvLmrReductionForTesting(false, quiet, 6, 8, 4) == 4,
                "extra reductions must clamp at depth minus two");
        check(search.adjustNonPvLmrReductionForTesting(false, quiet, 10, 8, 0) == 0,
                "a zero base reduction remains zero");

        Board captureBoard = board("3rk3/5n2/8/8/8/1r6/8/3QK3 w - - 0 1");
        int capture = find(captureBoard, "d1d8");
        check(search.adjustNonPvLmrReductionForTesting(false, capture, 10, 12, 2) == 2,
                "captures must not receive the non-PV extra reduction");
        Board promotionBoard = board("1r2k3/P7/8/8/8/8/8/4K3 w - - 0 1");
        int promotion = findAnyPromotion(promotionBoard);
        check(search.adjustNonPvLmrReductionForTesting(false, promotion, 10, 12, 2) == 2,
                "promotions must not receive the non-PV extra reduction");
        search.setNonPvLmrEnabledForTesting(false);
        check(search.adjustNonPvLmrReductionForTesting(false, quiet, 10, 8, 2) == 2,
                "the non-PV modifier must be independently toggleable");
    }

    private static void testInstrumentedSearchUsesReducedScouts() {
        Board board = new Board();
        Search search = new Search(new TranspositionTable(1));
        search.setInstrumentationEnabled(true);
        int best = search.search(board, 10, -1, null);
        Search.SearchStats stats = search.getLastStats();
        check(best != Move.NONE, "search must return a legal move");
        check(stats.lmrAttempts > 0 && stats.lmrReductionPlies >= stats.lmrAttempts,
                "instrumentation must count reduced scouts and total reduction plies");
        check(stats.nonPvLmrEligibleOpportunities > 0
                        && stats.nonPvLmrExtraPlyApplications > 0
                        && stats.nonPvLmrClampEvents <= stats.nonPvLmrEligibleOpportunities
                        && stats.nonPvLmrExtraPlyApplications <= stats.nonPvLmrEligibleOpportunities,
                "non-PV modifier opportunity, application, and clamp counters must be consistent");
        check(stats.nonPvLmrExtraPlyApplications
                        == stats.nonPvLmrReduction1To2 + stats.nonPvLmrReduction2To3
                            + stats.nonPvLmrReduction3To4,
                "instrumentation must bucket each extra ply by base-to-adjusted reduction");
        check(stats.lmrDeepReductions > 0,
                "instrumentation must report depth-plus-rank reductions greater than one ply");
        check(stats.lmrFullDepthResearches > 0
                        && stats.lmrFullDepthResearches <= stats.lmrReducedSearches,
                "reduced alpha raises must still receive nominal-depth re-searches");
        check(stats.toSummary().contains("nonPvLmrOpportunities=")
                        && stats.toSummary().contains("nonPvLmrExtraPly=")
                        && stats.toSummary().contains("nonPvLmrClamped=")
                        && stats.toSummary().contains("nonPvLmr1to2=")
                        && stats.toSummary().contains("nonPvLmr2to3=")
                        && stats.toSummary().contains("nonPvLmr3to4="),
                "non-PV modifier instrumentation must be exposed in the stats summary");

        Board draw = board("7k/8/8/8/8/8/8/Q6K w - - 100 1");
        search.search(draw, 1, -1, null);
        Search.SearchStats reset = search.getLastStats();
        check(reset.nonPvLmrEligibleOpportunities == 0
                        && reset.nonPvLmrExtraPlyApplications == 0
                        && reset.nonPvLmrClampEvents == 0
                        && reset.nonPvLmrReduction1To2 == 0
                        && reset.nonPvLmrReduction2To3 == 0
                        && reset.nonPvLmrReduction3To4 == 0,
                "non-PV modifier counters must reset for the next search");
    }

    private static int find(Board board, String uci) {
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        for (int i = 0; i < legal.size; i++) {
            if (Move.toUci(legal.get(i)).equals(uci)) return legal.get(i);
        }
        throw new AssertionError("missing legal move " + uci + " in " + board.toFen());
    }

    private static int findAnyPromotion(Board board) {
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        for (int i = 0; i < legal.size; i++) if (Move.isPromotion(legal.get(i))) return legal.get(i);
        throw new AssertionError("promotion fixture has no legal promotion");
    }

    private static Object field(Search search, String name) {
        try {
            Field field = Search.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(search);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Board board(String fen) {
        Board board = new Board();
        board.setFromFen(fen);
        return board;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

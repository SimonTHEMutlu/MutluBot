package engine;

import java.lang.reflect.Field;

/** Guard and reduction-depth checks for the main-search late-move reduction policy. */
public final class SearchLmrTest {
    public static void main(String[] args) {
        testRankAndDepthPolicy();
        testTacticalAndOrderingExemptions();
        testInstrumentedSearchUsesReducedScouts();
        System.out.println("SearchLmrTest passed");
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

    private static void testInstrumentedSearchUsesReducedScouts() {
        Board board = new Board();
        Search search = new Search(new TranspositionTable(1));
        search.setInstrumentationEnabled(true);
        int best = search.search(board, 10, -1, null);
        Search.SearchStats stats = search.getLastStats();
        check(best != Move.NONE, "search must return a legal move");
        check(stats.lmrAttempts > 0 && stats.lmrReductionPlies >= stats.lmrAttempts,
                "instrumentation must count reduced scouts and total reduction plies");
        check(stats.lmrDeepReductions > 0,
                "instrumentation must report depth-plus-rank reductions greater than one ply");
        check(stats.lmrFullDepthResearches <= stats.lmrReducedSearches,
                "nominal-depth researches cannot exceed reduced scouts");
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

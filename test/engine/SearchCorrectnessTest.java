package engine;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Regression tests for search terminal states and quiescence behavior. */
public final class SearchCorrectnessTest {
    private static final Method QUIESCENCE = quiescenceMethod();

    public static void main(String[] args) {
        testQuiescenceRecognizesCheckmate();
        testQuiescenceSearchesQuietCheckEvasions();
        testQuiescenceSearchesQuietPromotions();
        testThirdRepetitionInGameHistoryIsDraw();
        testThirdRepetitionWithRootOmittedIsDraw();
        testTwofoldRepetitionInGameHistoryIsNotDraw();
        testStaleTranspositionCannotForceThirdRepetition();
        testCheckmatePrecedesFiftyMoveDraw();
        testFiftyMoveDraw();
        System.out.println("SearchCorrectnessTest passed");
    }

    private static void testQuiescenceRecognizesCheckmate() {
        Board board = board("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1");
        String before = board.toFen();
        int ply = 7;
        int score = quiescence(board, ply);
        check(score == -(Evaluator.MATE_SCORE - ply),
                "quiescence must return mate score when the checked side has no evasion; got " + score);
        check(before.equals(board.toFen()), "checkmate quiescence must restore the board");
    }

    private static void testQuiescenceSearchesQuietCheckEvasions() {
        Board board = board("7k/8/8/7R/8/8/8/K7 b - - 0 1");
        String before = board.toFen();
        int score = quiescence(board, 3);
        check(score > -(Evaluator.MATE_SCORE - Search.MAX_PLY),
                "a checked side with quiet king evasions must not be scored as checkmated");
        check(before.equals(board.toFen()), "check-evasion quiescence must restore the board");
    }

    private static void testQuiescenceSearchesQuietPromotions() {
        Board board = board("8/P6k/8/8/8/8/8/7K w - - 0 1");
        String before = board.toFen();
        int standPat = Evaluator.evaluate(board);
        int score = quiescence(board, 0);
        check(score > standPat + 500,
                "quiescence must search a non-capture promotion; stand-pat=" + standPat + ", score=" + score);
        check(before.equals(board.toFen()), "promotion quiescence must restore the board");
    }

    private static void testThirdRepetitionInGameHistoryIsDraw() {
        HistoryPosition repeated = knightCycle(2);
        SearchResult result = search(repeated.board, repeated.keys);
        check(result.score == 0, "third repetition must be scored as a draw; got " + result.score);
        check(result.nodes == 1,
                "third repetition should terminate at the root; searched " + result.nodes + " nodes");
    }

    private static void testTwofoldRepetitionInGameHistoryIsNotDraw() {
        HistoryPosition repeated = knightCycle(1);
        SearchResult result = search(repeated.board, repeated.keys);
        check(result.nodes > 1, "twofold repetition must not terminate as an automatic draw");
    }

    private static void testThirdRepetitionWithRootOmittedIsDraw() {
        HistoryPosition repeated = knightCycle(2);
        long[] priorPositions = Arrays.copyOf(repeated.keys, repeated.keys.length - 1);
        SearchResult result = search(repeated.board, priorPositions);
        check(result.score == 0, "third repetition must be detected when history omits the root");
        check(result.nodes == 1,
                "third repetition with omitted root should terminate at the root; searched "
                        + result.nodes + " nodes");
    }

    /** Regression for Round 7 of mutlubotBetavsMutlubotVariedOpenings.pgn. */
    private static void testStaleTranspositionCannotForceThirdRepetition() {
        String[] moves = {
                "e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6",
                "b1c3", "a7a6", "f2f4", "e7e6", "d1f3", "d8b6", "d4b3", "b6c7",
                "a2a4", "b8c6", "c1e3", "e6e5", "f4f5", "f8e7", "e1c1", "e8g8",
                "c3d5", "f6d5", "e4d5", "c6a5", "b3d2", "c8d7", "f1d3", "b7b5",
                "a4b5", "d7b5", "c1b1", "a5c4", "d2c4", "b5c4", "d3e4", "a8b8",
                "e3c1", "b8b5", "b2b3", "f8b8", "b1a2", "c7b6", "f3e3", "b5b3",
                "e3b6", "b3b6", "a2a3", "b6b5", "c1d2", "c4d5", "e4d5", "b5d5",
                "d2b4", "d5b5", "c2c3", "d6d5", "h1e1", "d5d4", "e1e4", "e7b4",
                "c3b4", "a6a5", "g2g4", "f7f6", "h2h4", "a5b4", "a3b3", "b5c5",
                "h4h5", "c5c3", "b3b2", "c3g3", "d1d2", "b4b3", "b2b1", "g3g1",
                "b1b2", "g1g3", "b2b1", "g3g1", "b1b2"
        };
        HistoryPosition position = play(moves);
        check(position.board.toFen().equals(
                        "1r4k1/6pp/5p2/4pP1P/3pR1P1/1p6/1K1R4/6r1 b - - 7 43"),
                "Round 7 regression setup produced the wrong root: " + position.board.toFen());

        int drawingMove = findLegalMove(position.board, "g1g3");
        Board repeatedChild = board(position.board.toFen());
        repeatedChild.makeMove(findLegalMove(repeatedChild, "g1g3"));
        int occurrences = 1; // the child being considered
        for (long key : position.keys) if (key == repeatedChild.zobristKey) occurrences++;
        check(occurrences >= 3, "g1g3 must create the third occurrence in the supplied history");

        TranspositionTable shared = new TranspositionTable(16);
        shared.store(position.board.zobristKey, 12, 553,
                TranspositionTable.EXACT, drawingMove); // poison from the earlier root
        SearchResult result = search(position.board, position.keys, shared, 12);
        String bestMove = Move.toUci(result.move);
        check(!bestMove.equals("g1g3"),
                "a stale TT exact score must not force the immediate drawing move g1g3");
        check(result.score > 0,
                "the final root must retain a winning alternative instead of settling for repetition");
        check(result.pv.startsWith(bestMove),
                "reported PV must begin with the completed root move; bestmove=" + bestMove
                        + ", pv=" + result.pv);
    }

    private static void testCheckmatePrecedesFiftyMoveDraw() {
        Board board = board("7k/6Q1/6K1/8/8/8/8/8 b - - 100 1");
        SearchResult result = search(board, null);
        check(result.score == -Evaluator.MATE_SCORE,
                "checkmate must take precedence over a fifty-move draw; got " + result.score);
    }

    private static void testFiftyMoveDraw() {
        Board board = board("7k/8/8/8/8/8/8/Q6K w - - 100 1");
        SearchResult result = search(board, null);
        check(result.score == 0, "an eligible fifty-move position must be scored as a draw");
        check(result.nodes == 1, "a fifty-move draw should terminate at the root");
    }

    private static SearchResult search(Board board, long[] history) {
        return search(board, history, new TranspositionTable(1), 1);
    }

    private static SearchResult search(Board board, long[] history,
                                       TranspositionTable tt, int depth) {
        SearchResult result = new SearchResult();
        Search search = new Search(tt);
        search.setInfoListener((completedDepth, selDepth, score, mate, mateIn, nodes, nps, timeMs, pv) -> {
            if (completedDepth == result.requestedDepth) {
                result.score = score;
                result.nodes = nodes;
                result.pv = pv;
            }
        });
        result.requestedDepth = depth;
        result.move = search.search(board, depth, -1, history);
        return result;
    }

    private static HistoryPosition knightCycle(int cycles) {
        Board board = new Board();
        long[] keys = new long[cycles * 4 + 1];
        keys[0] = board.zobristKey;
        String[] moves = {"g1f3", "g8f6", "f3g1", "f6g8"};
        for (int i = 0; i < cycles * moves.length; i++) {
            board.makeMove(findLegalMove(board, moves[i % moves.length]));
            keys[i + 1] = board.zobristKey;
        }
        return new HistoryPosition(board, keys);
    }

    private static HistoryPosition play(String[] moves) {
        Board board = new Board();
        long[] keys = new long[moves.length + 1];
        keys[0] = board.zobristKey;
        for (int i = 0; i < moves.length; i++) {
            board.makeMove(findLegalMove(board, moves[i]));
            keys[i + 1] = board.zobristKey;
        }
        return new HistoryPosition(board, keys);
    }

    private static int findLegalMove(Board board, String uci) {
        MoveList moves = new MoveList();
        MoveGenerator.generateLegal(board, moves);
        for (int i = 0; i < moves.size; i++) {
            int move = moves.get(i);
            if (Move.toUci(move).equals(uci)) return move;
        }
        throw new AssertionError("No legal move " + uci + " in " + board.toFen());
    }

    private static int quiescence(Board board, int ply) {
        Search search = new Search(new TranspositionTable(1));
        try {
            return (Integer) QUIESCENCE.invoke(search, board,
                    -Evaluator.INFINITY_SCORE, Evaluator.INFINITY_SCORE, ply);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }

    private static Method quiescenceMethod() {
        try {
            Method method = Search.class.getDeclaredMethod(
                    "quiescence", Board.class, int.class, int.class, int.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
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

    private static final class HistoryPosition {
        final Board board;
        final long[] keys;

        HistoryPosition(Board board, long[] keys) {
            this.board = board;
            this.keys = keys;
        }
    }

    private static final class SearchResult {
        int requestedDepth;
        int move;
        int score;
        long nodes;
        String pv = "";
    }
}

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
        SearchResult result = new SearchResult();
        Search search = new Search(new TranspositionTable(1));
        search.setInfoListener((depth, selDepth, score, mate, mateIn, nodes, nps, timeMs, pv) -> {
            if (depth == 1) {
                result.score = score;
                result.nodes = nodes;
            }
        });
        search.search(board, 1, -1, history);
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
        int score;
        long nodes;
    }
}

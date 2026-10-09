package engine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/** Focused checks for SEE stages through the selector used by main search. */
public final class SearchMovePickerTest {
    private static final Method SCORE_MOVES = method("scoreMoves", Board.class, MoveList.class,
            int.class, int.class, int[].class);
    private static final Method SCORE_CAPTURES = method("scoreCaptures", Board.class,
            MoveList.class, int[].class);
    private static final Method SELECT_NEXT = method("selectNextMove", Board.class,
            MoveList.class, int[].class, int.class, int.class, int.class);

    public static void main(String[] args) {
        testTtAndStaleMovesAndNoDuplicates();
        testSeeStages();
        testLosingCaptureReductionGuards();
        testKillerAndHistoryStages();
        testCheckEvasionsAreRetained();
        testRepresentativeMoveSetsAreRetained();
        System.out.println("SearchMovePickerTest passed");
    }

    private static void testRepresentativeMoveSetsAreRetained() {
        String[] fens = {
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
                "3rk3/5n2/8/8/8/1r6/8/3QK3 w - - 0 1"
        };
        for (String fen : fens) {
            Board board = board(fen);
            MoveList before = pseudo(board);
            MoveList after = pseudo(board);
            order(new Search(new TranspositionTable(1)), board, after, Move.NONE, false);
            check(before.size == after.size, "stage selection preserves move count");
            assertNoDuplicates(after);
            for (int i = 0; i < before.size; i++) {
                check(contains(after, before.get(i)), "stage selection dropped a move");
            }
            boolean quietSeen = false;
            for (int i = 0; i < after.size; i++) {
                int move = after.get(i);
                if (!Move.isCapture(move) && !Move.isPromotion(move)) quietSeen = true;
                if (Move.isCapture(move) && !Move.isPromotion(move)) {
                    int see = StaticExchange.evaluate(board, move, new int[32]);
                    check(see > -300 ? !quietSeen : quietSeen,
                            "only clearly losing captures should follow quiets");
                }
            }
        }
    }

    private static void testLosingCaptureReductionGuards() {
        Search search = new Search(new TranspositionTable(1));
        Board board = board("4k3/8/8/4r3/4p3/8/4Q3/4K3 w - - 0 1");
        int bad = find(pseudo(board), "e2e4");
        check(search.lmrReductionForMove(bad, 10, 10, 4, Move.NONE, false, false, false, -300) > 0,
                "strongly losing late capture is eligible for a reduced scout");
        check(search.lmrReductionForMove(bad, 10, 10, 4, Move.NONE, false, false, false, -299) == 0,
                "modest losses remain protected");
        check(search.lmrReductionForMove(bad, 2, 10, 4, Move.NONE, false, false, false, -800) == 0,
                "shallow captures remain protected");
        check(search.lmrReductionForMove(bad, 10, 4, 4, Move.NONE, false, false, false, -800) == 0,
                "early captures remain protected");
        check(search.lmrReductionForMove(bad, 10, 10, 4, bad, false, false, false, -800) == 0,
                "TT losing captures remain protected");
        check(search.lmrReductionForMove(bad, 10, 10, 4, Move.NONE, true, false, false, -800) == 0,
                "check evasions remain protected");
        check(search.lmrReductionForMove(bad, 10, 10, 4, Move.NONE, false, true, false, -800) == 0,
                "checking captures remain protected");
        check(search.lmrReductionForMove(bad, 10, 10, 4, Move.NONE, false, false, false) == 0,
                "legacy helper protects captures without cached SEE");
        Board ep = board("3rk3/8/8/3pP3/8/8/8/4K3 w - d6 0 1");
        int epMove = find(pseudo(ep), "e5d6");
        check(search.lmrReductionForMove(epMove, 10, 10, 4, Move.NONE, false, false, false, -800) == 0,
                "en passant remains protected regardless of supplied SEE");
        Board promotion = board("1r2k3/P7/8/8/8/8/8/4K3 w - - 0 1");
        int promotionMove = find(pseudo(promotion), "a7b8q");
        check(search.lmrReductionForMove(promotionMove, 10, 10, 4, Move.NONE, false, false, false, -800) == 0,
                "capture promotions remain protected");
    }

    private static void testSeeStages() {
        Board badBoard = board("4k3/8/8/4r3/4p3/8/4Q3/4K3 w - - 0 1");
        for (int ply = 0; ply < 4; ply++) {
            MoveList nearRoot = pseudo(badBoard);
            int bad = find(nearRoot, "e2e4");
            order(new Search(new TranspositionTable(1)), badBoard, nearRoot, Move.NONE, false, 5, ply);
            for (int i = 0; i < nearRoot.size; i++) {
                if (!Move.isCapture(nearRoot.get(i)) && !Move.isPromotion(nearRoot.get(i))) {
                    check(i < index(nearRoot, bad), "normal root and near-root quiets precede losing captures");
                }
            }
        }
        for (int depth : new int[] {1, 4, 5, 10}) {
            Search search = new Search(new TranspositionTable(1));
            MoveList moves = pseudo(badBoard);
            int bad = find(moves, "e2e4");
            int killer = find(moves, "e2d2");
            int quiet = find(moves, "e2f2");
            ((int[][]) field(search, "killerMoves"))[3][0] = killer;
            ((int[][]) field(search, "historyTable"))[Move.from(quiet)][Move.to(quiet)] = 100000;
            check(StaticExchange.evaluate(badBoard, bad, new int[32]) == -800,
                    "fixture must lose a queen for a pawn");
            order(search, badBoard, moves, Move.NONE, false, depth);
            check(index(moves, killer) < index(moves, quiet), "killer precedes history quiet");
            for (int i = 0; i < moves.size; i++) {
                if (!Move.isCapture(moves.get(i)) && !Move.isPromotion(moves.get(i))) {
                    check(i < index(moves, bad), "all quiets precede losing capture at depth " + depth);
                }
            }
            MoveList tt = pseudo(badBoard);
            order(search, badBoard, tt, bad, false, depth);
            check(tt.get(0) == bad, "losing TT capture must still be first");
        }
        assertCaptureBeforeQuiets("4k3/8/8/8/4q3/8/4R3/4K3 w - - 0 1", "e2e4", 900);
        assertCaptureBeforeQuiets("4k3/8/8/4r3/4r3/8/4R3/4K3 w - - 0 1", "e2e4", 0);
        assertMildlyLosingCaptureBeforeQuiets();
        assertCaptureBeforeQuiets("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", "e5d6", 100);
        assertCaptureBeforeQuiets("3rk3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", "e5d6", 0);

        Board promotionsBoard = board("1r2k3/P7/8/8/4q3/8/4R3/4K3 w - - 0 1");
        MoveList promotions = pseudo(promotionsBoard);
        order(new Search(new TranspositionTable(1)), promotionsBoard, promotions, Move.NONE, false);
        int goodCapture = find(promotions, "e2e4");
        int promotionCount = 0;
        for (int i = 0; i < promotions.size; i++) {
            if (!Move.isPromotion(promotions.get(i))) continue;
            promotionCount++;
            for (int j = 0; j < promotions.size; j++) {
                if (!Move.isCapture(promotions.get(j)) && !Move.isPromotion(promotions.get(j))) {
                    check(i < j, "every underpromotion and capture promotion precedes quiets");
                }
            }
        }
        check(promotionCount == 8, "all quiet and capture promotion choices retained");
        check(index(promotions, goodCapture) < index(promotions, find(promotions, "a7a8q")),
                "winning ordinary capture precedes quiet promotion");
        assertNoDuplicates(promotions);
    }

    private static void assertMildlyLosingCaptureBeforeQuiets() {
        Board board = board("4k3/4r3/8/8/4n3/8/4R3/4K3 w - - 0 1");
        MoveList moves = pseudo(board);
        int capture = find(moves, "e2e4");
        int see = StaticExchange.evaluate(board, capture, new int[32]);
        check(see > -300 && see < 0, "mild-loss fixture should have a negative SEE above -300");
        order(new Search(new TranspositionTable(1)), board, moves, Move.NONE, false);
        for (int i = 0; i < moves.size; i++) {
            if (!Move.isCapture(moves.get(i)) && !Move.isPromotion(moves.get(i))) {
                check(index(moves, capture) < i,
                        "mildly losing captures remain in the tactical stage");
            }
        }
    }

    private static int index(MoveList moves, int move) {
        for (int i = 0; i < moves.size; i++) if (moves.get(i) == move) return i;
        return -1;
    }

    private static void assertCaptureBeforeQuiets(String fen, String uci, int expectedSee) {
        Board board = board(fen);
        String before = board.toFen();
        MoveList moves = pseudo(board);
        int capture = find(moves, uci);
        check(StaticExchange.evaluate(board, capture, new int[32]) == expectedSee, "fixture SEE " + uci);
        order(new Search(new TranspositionTable(1)), board, moves, Move.NONE, false);
        for (int i = 0; i < moves.size; i++) {
            if (!Move.isCapture(moves.get(i)) && !Move.isPromotion(moves.get(i))) {
                check(index(moves, capture) < i, "winning/equal capture precedes quiets: " + uci);
            }
        }
        check(board.toFen().equals(before), "capture classification preserves board state");
    }

    private static void testTtAndStaleMovesAndNoDuplicates() {
        Board board = new Board();
        Search search = new Search(new TranspositionTable(1));
        MoveList moves = pseudo(board);
        int ttMove = find(moves, "e2e4");
        order(search, board, moves, ttMove, false);
        check(Move.toUci(moves.get(0)).equals("e2e4"), "pseudo-legal TT move must be first");
        assertNoDuplicates(moves);

        MoveList stale = pseudo(board);
        order(search, board, stale, Move.encode(Bitboards.squareFromName("a1"),
                Bitboards.squareFromName("h8"), Move.QUIET), false);
        check(stale.size == pseudo(board).size, "stale TT move must not be inserted");
        assertNoDuplicates(stale);
    }

    private static void testKillerAndHistoryStages() {
        Board board = new Board();
        Search killerSearch = new Search(new TranspositionTable(1));
        int killer = find(pseudo(board), "e2e4");
        int[][] killers = (int[][]) field(killerSearch, "killerMoves");
        killers[3][0] = killer;
        MoveList moves = pseudo(board);
        order(killerSearch, board, moves, Move.NONE, false);
        check(moves.get(0) == killer, "killer quiet must precede ordinary quiets");

        Search historySearch = new Search(new TranspositionTable(1));
        int historyMove = find(pseudo(board), "d2d4");
        int[][] history = (int[][]) field(historySearch, "historyTable");
        history[Move.from(historyMove)][Move.to(historyMove)] = 100000;
        moves = pseudo(board);
        order(historySearch, board, moves, Move.NONE, false);
        check(moves.get(0) == historyMove, "highest-history quiet must lead quiet stage");
    }

    private static void testCheckEvasionsAreRetained() {
        Board board = board("4k3/8/8/8/8/8/4r3/4K3 w - - 0 1");
        MoveList original = pseudo(board);
        MoveList ordered = pseudo(board);
        order(new Search(new TranspositionTable(1)), board, ordered, Move.NONE, true);
        check(ordered.size == original.size, "qsearch check ordering must retain all evasions");
        assertNoDuplicates(ordered);
        for (int i = 0; i < original.size; i++) {
            check(contains(ordered, original.get(i)), "qsearch ordering dropped a check evasion");
        }
    }

    private static MoveList pseudo(Board board) {
        MoveList moves = new MoveList();
        MoveGenerator.generatePseudoLegal(board, moves, false);
        return moves;
    }

    private static void order(Search search, Board board, MoveList moves, int ttMove,
                              boolean quiescence) {
        order(search, board, moves, ttMove, quiescence, 5);
    }

    private static void order(Search search, Board board, MoveList moves, int ttMove,
                              boolean quiescence, int depth) {
        order(search, board, moves, ttMove, quiescence, depth, 3);
    }

    private static void order(Search search, Board board, MoveList moves, int ttMove,
                              boolean quiescence, int depth, int ply) {
        try {
            int[] scores = new int[Math.max(256, moves.size)];
            boolean checkEvasions = quiescence && board.isInCheck(board.sideToMove);
            if (quiescence && !checkEvasions) SCORE_CAPTURES.invoke(search, board, moves, scores);
            else SCORE_MOVES.invoke(search, board, moves, ttMove, ply, scores);
            for (int i = 0; i < moves.size; i++) {
                int bestIdx = i;
                for (int j = i + 1; j < moves.size; j++) if (scores[j] > scores[bestIdx]) bestIdx = j;
                if (!quiescence) bestIdx = (Integer) SELECT_NEXT.invoke(search, board, moves, scores,
                        i, depth, ply);
                if (bestIdx != i) {
                    int tmpM = moves.moves[i]; moves.moves[i] = moves.moves[bestIdx]; moves.moves[bestIdx] = tmpM;
                    int tmpS = scores[i]; scores[i] = scores[bestIdx]; scores[bestIdx] = tmpS;
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            Method method = Search.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
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

    private static int find(MoveList moves, String uci) {
        for (int i = 0; i < moves.size; i++) if (Move.toUci(moves.get(i)).equals(uci)) return moves.get(i);
        throw new AssertionError("missing move " + uci);
    }

    private static boolean contains(MoveList moves, int move) {
        for (int i = 0; i < moves.size; i++) if (moves.get(i) == move) return true;
        return false;
    }

    private static void assertNoDuplicates(MoveList moves) {
        Set<Integer> unique = new HashSet<>();
        for (int i = 0; i < moves.size; i++) check(unique.add(moves.get(i)), "move picker produced a duplicate");
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

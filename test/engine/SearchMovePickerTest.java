package engine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/** Focused checks for legacy ordering plus the rare selection-time SEE tie-break. */
public final class SearchMovePickerTest {
    private static final Method SCORE_MOVES = method("scoreMoves", Board.class, MoveList.class,
            int.class, int.class, int[].class);
    private static final Method SCORE_CAPTURES = method("scoreCaptures", Board.class,
            MoveList.class, int[].class);
    private static final Method DEMOTE = method("maybeDemoteLosingCapture", Board.class,
            MoveList.class, int[].class, int.class, int.class, int.class);

    public static void main(String[] args) {
        testTtAndStaleMovesAndNoDuplicates();
        testPromotionAndCaptureStages();
        testKillerAndHistoryStages();
        testCheckEvasionsAreRetained();
        testLegacyParityOnRepresentativeRoots();
        testTieBreakGates();
        System.out.println("SearchMovePickerTest passed");
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

    private static void testPromotionAndCaptureStages() {
        Board promotionBoard = board("1r2k3/P7/8/8/8/8/8/4K3 w - - 0 1");
        MoveList promotions = pseudo(promotionBoard);
        order(new Search(new TranspositionTable(1)), promotionBoard, promotions, Move.NONE, false);
        int promotionCount = 0;
        for (int i = 0; i < promotions.size; i++) if (Move.isPromotion(promotions.get(i))) promotionCount++;
        check(promotionCount > 0, "promotion fixture must contain promotions");
        for (int i = 0; i < promotionCount; i++) {
            check(Move.isPromotion(promotions.get(i)),
                    "promotions must precede non-promotions: " + Move.toUci(promotions.get(i)));
        }

        Board captureBoard = board("3rk3/5n2/8/8/8/1r6/8/3QK3 w - - 0 1");
        MoveList captures = pseudo(captureBoard);
        int captureBandSize = 0;
        for (int i = 0; i < captures.size; i++) if (Move.isCapture(captures.get(i))) captureBandSize++;
        order(new Search(new TranspositionTable(1)), captureBoard, captures, Move.NONE, false);
        for (int i = 0; i < captureBandSize; i++) {
            check(Move.isCapture(captures.get(i)), "capture class must precede promotions and quiets");
        }
        boolean sawGood = false;
        boolean sawBad = false;
        int captureCount = 0;
        int previousMvv = Integer.MAX_VALUE;
        boolean previousBad = false;
        for (int i = 0; i < captures.size; i++) {
            int move = captures.get(i);
            if (!Move.isCapture(move) || Move.isPromotion(move)) continue;
            captureCount++;
            int see = StaticExchange.evaluate(captureBoard, move, new int[32]);
            int mvv = mvvFor(captureBoard, move);
            check(mvv <= previousMvv, "captures must remain primarily MVV-LVA ordered");
            if (mvv == previousMvv && previousBad && see >= 0) {
                throw new AssertionError("a losing capture was not deprioritized within an equal MVV tie");
            }
            previousMvv = mvv;
            previousBad = see < 0;
            if (see < 0) sawBad = true;
            else {
                sawGood = true;
            }
        }
        check(sawGood && sawBad, "capture fixture must contain good and losing captures: " + captureCount);
        check(Move.toUci(captures.get(0)).equals("d1b3"),
                "strongly losing equal-MVV capture must be demoted behind the safe tie");
    }

    private static void testTieBreakGates() {
        Board board = board("3rk3/5n2/8/8/8/1r6/8/3QK3 w - - 0 1");
        MoveList shallow = pseudo(board);
        order(new Search(new TranspositionTable(1)), board, shallow, Move.NONE, false, 4);
        MoveList shallowLegacy = pseudo(board);
        legacyOrder(new Search(new TranspositionTable(1)), board, shallowLegacy);
        check(sameMoves(shallow, shallowLegacy),
                "main SEE tie-break must be disabled below remaining depth 5");

        MoveList deep = pseudo(board);
        order(new Search(new TranspositionTable(1)), board, deep, Move.NONE, false, 5);
        check(Move.toUci(deep.get(0)).equals("d1b3"),
                "strong losing capture must be demoted at remaining depth 5");
        check(seeIfEligible(board, find(pseudo(board), "d1d8")) <= -300,
                "demotion fixture must contain a capture at or below the strong-loss threshold");

        Board equalValue = board("3rk3/8/8/8/8/8/8/1r1RK3 w - - 0 1");
        MoveList equalValueMoves = pseudo(equalValue);
        order(new Search(new TranspositionTable(1)), equalValue, equalValueMoves, Move.NONE, false, 5);
        MoveList equalValueLegacy = pseudo(equalValue);
        legacyOrder(new Search(new TranspositionTable(1)), equalValue, equalValueLegacy);
        check(sameMoves(equalValueMoves, equalValueLegacy),
                "equal-value attacker/victim tie must retain legacy order");

        Board promotion = board("1r2k3/P7/8/8/8/1r6/8/3QK3 w - - 0 1");
        MoveList promotionMoves = pseudo(promotion);
        MoveList promotionLegacy = pseudo(promotion);
        boolean sawPromotionCapture = false;
        for (int i = 0; i < promotionMoves.size; i++) {
            if (Move.isPromotion(promotionMoves.get(i)) && Move.isCapture(promotionMoves.get(i))) {
                sawPromotionCapture = true;
                break;
            }
        }
        check(sawPromotionCapture, "promotion fixture must contain a promotion capture");
        order(new Search(new TranspositionTable(1)), promotion, promotionMoves, Move.NONE, false, 5);
        legacyOrder(new Search(new TranspositionTable(1)), promotion, promotionLegacy);
        check(sameMoves(promotionMoves, promotionLegacy),
                "promotion captures must retain legacy order");

        Board ep = board("4k3/8/8/3pP3/8/1r6/8/3QK3 w - d6 0 1");
        MoveList epMoves = pseudo(ep);
        MoveList epLegacy = pseudo(ep);
        check(containsEnPassant(epMoves), "en-passant fixture must contain an en-passant capture");
        order(new Search(new TranspositionTable(1)), ep, epMoves, Move.NONE, false, 5);
        legacyOrder(new Search(new TranspositionTable(1)), ep, epLegacy);
        check(sameMoves(epMoves, epLegacy), "en-passant captures must retain legacy order");
    }

    private static void testKillerAndHistoryStages() {
        Board board = new Board();
        Search killerSearch = new Search(new TranspositionTable(1));
        int killer = find(pseudo(board), "e2e4");
        int[][] killers = (int[][]) field(killerSearch, "killerMoves");
        killers[0][0] = killer;
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

    private static void testLegacyParityOnRepresentativeRoots() {
        String[] fens = {
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
                "r1bqk2r/ppp2ppp/2np1n2/2b1p3/2B1P3/2PP1N2/PP3PPP/RNBQK2R w KQkq - 0 6"
        };
        for (String fen : fens) {
            Board board = board(fen);
            Search search = new Search(new TranspositionTable(1));
            MoveList legacy = pseudo(board);
            int[] rawScores = rawScores(search, board, legacy);
            legacySelectionSort(legacy, rawScores);
            MoveList modern = pseudo(board);
            if (hasQualifyingStrongLoserTie(board, legacy, rawScores)) continue;
            order(search, board, modern, Move.NONE, false);
            check(sameMoves(legacy, modern), "legacy parity changed at root: " + fen);
        }
    }

    private static void legacyOrder(Search search, Board board, MoveList moves) {
        try {
            int[] scores = new int[Math.max(256, moves.size)];
            SCORE_MOVES.invoke(search, board, moves, Move.NONE, 0, scores);
            legacySelectionSort(moves, scores);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int[] rawScores(Search search, Board board, MoveList moves) {
        try {
            int[] scores = new int[Math.max(256, moves.size)];
            SCORE_MOVES.invoke(search, board, moves, Move.NONE, 0, scores);
            return scores;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void legacySelectionSort(MoveList moves, int[] scores) {
        for (int i = 0; i < moves.size; i++) {
            int bestIdx = i;
            for (int j = i + 1; j < moves.size; j++) if (scores[j] > scores[bestIdx]) bestIdx = j;
            if (bestIdx != i) {
                int tmpM = moves.moves[i]; moves.moves[i] = moves.moves[bestIdx]; moves.moves[bestIdx] = tmpM;
                int tmpS = scores[i]; scores[i] = scores[bestIdx]; scores[bestIdx] = tmpS;
            }
        }
    }

    private static boolean hasQualifyingStrongLoserTie(Board board, MoveList moves, int[] scores) {
        for (int i = 0; i < moves.size; i++) {
            if (!eligibleMainTieCapture(board, moves.get(i))) continue;
            int rawScore = scores[i];
            boolean tied = false;
            for (int j = i + 1; j < moves.size; j++) {
                if (scores[j] == rawScore && eligibleMainTieCapture(board, moves.get(j))) {
                    tied = true;
                    break;
                }
            }
            if (!tied) continue;
            int selectedSee = seeIfEligible(board, moves.get(i));
            if (selectedSee > -300) continue;
            for (int j = i + 1; j < moves.size; j++) {
                if (scores[j] == rawScore && eligibleMainTieCapture(board, moves.get(j))
                        && seeIfEligible(board, moves.get(j)) >= 0) return true;
            }
        }
        return false;
    }

    private static int seeIfEligible(Board board, int move) {
        if (!eligibleMainTieCapture(board, move)) return 0;
        int attacker = board.pieceTypeAt(Move.from(move));
        int victim = board.pieceTypeAt(Move.to(move));
        if (attacker < 0 || victim < 0 || Evaluator.PIECE_VALUE[attacker] <= Evaluator.PIECE_VALUE[victim]) return 0;
        return StaticExchange.evaluate(board, move, new int[32]);
    }

    private static boolean eligibleMainTieCapture(Board board, int move) {
        if (!Move.isCapture(move) || Move.isPromotion(move) || Move.isEnPassant(move)) return false;
        int attacker = board.pieceTypeAt(Move.from(move));
        int victim = board.pieceTypeAt(Move.to(move));
        return attacker >= 0 && victim >= 0
                && Evaluator.PIECE_VALUE[attacker] > Evaluator.PIECE_VALUE[victim];
    }

    private static boolean sameMoves(MoveList a, MoveList b) {
        if (a.size != b.size) return false;
        for (int i = 0; i < a.size; i++) if (a.get(i) != b.get(i)) return false;
        return true;
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
        try {
            int[] scores = new int[Math.max(256, moves.size)];
            boolean checkEvasions = quiescence && board.isInCheck(board.sideToMove);
            if (quiescence && !checkEvasions) SCORE_CAPTURES.invoke(search, board, moves, scores);
            else SCORE_MOVES.invoke(search, board, moves, ttMove, 0, scores);
            for (int i = 0; i < moves.size; i++) {
                int bestIdx = i;
                for (int j = i + 1; j < moves.size; j++) if (scores[j] > scores[bestIdx]) bestIdx = j;
                if (!quiescence) bestIdx = (Integer) DEMOTE.invoke(search, board, moves, scores,
                        bestIdx, depth, 0);
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

    private static int mvvFor(Board board, int move) {
        int victim = Move.isEnPassant(move) ? Piece.PAWN : board.pieceTypeAt(Move.to(move));
        int attacker = board.pieceTypeAt(Move.from(move));
        int score = (victim >= 0 ? Evaluator.PIECE_VALUE[victim] : Evaluator.PAWN_VALUE) * 16
                - (attacker >= 0 ? Evaluator.PIECE_VALUE[attacker] : 0);
        if (Move.isPromotion(move)) score += Evaluator.PIECE_VALUE[Move.promotionPieceType(move)];
        return score;
    }

    private static boolean contains(MoveList moves, int move) {
        for (int i = 0; i < moves.size; i++) if (moves.get(i) == move) return true;
        return false;
    }

    private static boolean containsEnPassant(MoveList moves) {
        for (int i = 0; i < moves.size; i++) if (Move.isEnPassant(moves.get(i))) return true;
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

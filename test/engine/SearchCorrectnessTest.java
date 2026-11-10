package engine;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Regression tests for search terminal states and quiescence behavior. */
public final class SearchCorrectnessTest {
    private static final Method QUIESCENCE = quiescenceMethod();

    public static void main(String[] args) {
        testQuiescenceRecognizesCheckmate();
        testQuiescenceRecognizesStalemate();
        testAllocationFreeAnyLegalMoveMatchesLegalGeneration();
        testKingMoveMaskMatchesLegalGeneration();
        testLegalKingMovePrefilter();
        testQuiescenceSearchesQuietCheckEvasions();
        testQuiescenceSearchesQuietPromotions();
        testThirdRepetitionInGameHistoryIsDraw();
        testThirdRepetitionWithRootOmittedIsDraw();
        testTwofoldRepetitionInGameHistoryIsNotDraw();
        testGameOneAvoidsSearchCycle();
        testQuiescenceTwofoldSearchCycleIsDraw();
        testStaleTranspositionCannotForceThirdRepetition();
        testIllegalRootTranspositionMoveCannotBecomeBestMove();
        testPvStopsAtStaleOrNonExactTtEntries();
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

    private static void testQuiescenceRecognizesStalemate() {
        Board board = board("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
        String before = board.toFen();
        check(!MoveGenerator.hasLegalKingMove(board),
                "stalemated king must have no legal adjacent move");
        check(!MoveGenerator.hasLegalMove(board), "stalemate setup must have no legal move");
        int score = quiescence(board, 0);
        check(score == 0, "quiescence must score stalemate as a draw; got " + score);
        check(before.equals(board.toFen()), "stalemate quiescence must restore the board");
    }

    private static void testLegalKingMovePrefilter() {
        Board board = new Board();
        String before = board.toFen();
        check(!MoveGenerator.hasLegalKingMove(board),
                "blocked starting king must have no legal adjacent move");
        check(MoveGenerator.hasLegalPawnMove(board, new MoveList()),
                "starting position must have a legal pawn move");
        check(before.equals(board.toFen()), "king move prefilter must restore the board");

        board.setFromFen("7k/8/8/8/8/8/8/K7 w - - 0 1");
        before = board.toFen();
        check(MoveGenerator.hasLegalKingMove(board),
                "open king must have at least one legal adjacent move");
        check(before.equals(board.toFen()), "successful king prefilter must restore the board");

        board.setFromFen("k7/8/8/8/8/8/K1P4r/8 w - - 0 1");
        before = board.toFen();
        check(!MoveGenerator.hasLegalPawnMove(board, new MoveList()),
                "pinned pawn moves that expose the king must not pass the prefilter");
        check(before.equals(board.toFen()), "pinned pawn prefilter must restore the board");
    }

    private static void testAllocationFreeAnyLegalMoveMatchesLegalGeneration() {
        assertAnyLegalMoveMatches("checkmate", "7k/6Q1/6K1/8/8/8/8/8 b - - 0 1", false);
        assertAnyLegalMoveMatches("stalemate", "7k/5Q2/6K1/8/8/8/8/8 b - - 0 1", false);
        assertAnyLegalMoveMatches("stalemate with pinned pawn", "7k/5Qp1/8/4B3/4B3/8/8/K7 b - - 0 1", false);
        assertAnyLegalMoveMatches("quiet check evasion", "7k/8/8/7R/8/8/8/K7 b - - 0 1", true);
        assertAnyLegalMoveMatches("castling", "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", true);
        assertAnyLegalMoveMatches("promotion", "7k/P7/8/8/8/8/8/7K w - - 0 1", true);
        assertAnyLegalMoveMatches("legal en passant", "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", true);
        assertAnyLegalMoveMatches("pinned en passant", "4k3/8/8/r4PpK/8/8/8/8 w - g6 0 1", true);
        assertAnyLegalMoveMatches("pinned piece", "k7/8/8/8/8/8/4r3/4K2R w K - 0 1", true);
        check(hasLegalUci("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", "e5d6"),
                "legal en passant capture must remain available");
        check(!hasLegalUci("4k3/8/8/r4PpK/8/8/8/8 w - g6 0 1", "f5g6"),
                "en passant that exposes the king must remain illegal");
        check(hasLegalUci("7k/P7/8/8/8/8/8/7K w - - 0 1", "a7a8q"),
                "promotion must remain available to legal-move detection");
        check(hasLegalUci("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1", "e1g1"),
                "legal castling must remain available");
    }

    private static void assertAnyLegalMoveMatches(String label, String fen, boolean expected) {
        Board board = board(fen);
        String before = board.toFen();
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        boolean reference = legal.size != 0;
        check(reference == expected, label + " fixture must have expected legal-move state");
        MoveList scratch = new MoveList();
        check(MoveGenerator.hasAnyLegalMove(board, scratch) == reference,
                "allocation-free legal-move check disagrees with full generation: " + label);
        check(before.equals(board.toFen()), "legal-move check must restore board: " + label);
    }

    private static boolean hasLegalUci(String fen, String uci) {
        Board board = board(fen);
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        for (int i = 0; i < legal.size; i++) {
            if (Move.toUci(legal.get(i)).equals(uci)) return true;
        }
        return false;
    }

    private static void testKingMoveMaskMatchesLegalGeneration() {
        String undefendedCapture = "k7/8/8/4p3/4K3/8/8/8 w - - 0 1";
        String defendedCapture = "k3r3/8/8/4p3/4K3/8/8/8 w - - 0 1";
        String discoveredRookAttack = "4k3/8/8/8/4K3/8/4r3/8 w - - 0 1";
        assertKingMoveProbeMatches(undefendedCapture);
        assertKingMoveProbeMatches(defendedCapture);
        assertKingMoveProbeMatches(discoveredRookAttack);
        check(hasLegalUci(undefendedCapture, "e4e5"),
                "king must be able to capture an undefended adjacent piece");
        check(!hasLegalUci(defendedCapture, "e4e5"),
                "king must not capture an adjacent piece defended by a rook");
        check(!hasLegalUci(discoveredRookAttack, "e4e5"),
                "king must not move into a rook ray uncovered by vacating its source");
    }

    private static void assertKingMoveProbeMatches(String fen) {
        Board board = board(fen);
        String before = board.toFen();
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        boolean hasLegalAdjacentKingMove = false;
        for (int i = 0; i < legal.size; i++) {
            int move = legal.get(i);
            if (board.pieceTypeAt(Move.from(move)) == Piece.KING && !Move.isCastle(move)) {
                hasLegalAdjacentKingMove = true;
                break;
            }
        }
        check(MoveGenerator.hasLegalKingMove(board) == hasLegalAdjacentKingMove,
                "king attack-mask probe disagrees with legal generation in " + fen);
        check(before.equals(board.toFen()), "king attack-mask probe must preserve board");
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

    /** Regression for the winning rook ending in MutlubotBetaKingSafetyUpdateVSPhallanx24.pgn. */
    private static void testGameOneAvoidsSearchCycle() {
        String[] moves = {
                "g1f3", "e7e6", "g2g3", "d7d5", "f1g2", "c7c5", "e2e3", "b8c6",
                "d2d4", "g8f6", "e1g1", "f8e7", "b1d2", "e8g8", "c2c4", "c5d4",
                "e3d4", "c8d7", "f1e1", "d5c4", "d2c4", "a8c8", "a2a3", "c6a5",
                "c4e5", "f6d5", "e5d7", "d8d7", "f3e5", "d7d6", "d1a4", "a5c6",
                "e5c6", "d6c6", "a4c6", "b7c6", "b2b4", "c8b8", "a1a2", "b8b6",
                "c1b2", "e7f6", "e1d1", "f8b8", "d1d2", "b8a8", "g2e4", "b6b8",
                "d2c2", "a7a5", "e4d5", "c6d5", "b2c3", "b8c8", "b4a5", "c8c4",
                "c3d2", "c4d4", "a3a4", "d4d3", "c2c6", "h7h5", "a5a6", "g7g5",
                "g1f1", "g5g4", "a4a5", "f6d4", "f1e2", "d3b3", "d2e3", "d4e5",
                "a2a4", "g8g7", "a6a7", "b3b1", "c6b6", "b1g1", "f2f4", "g4f3",
                "e2f3", "g1e1", "b6b7", "e1f1", "f3e2", "f1h1", "a4h4", "g7g6",
                "g3g4", "h5g4", "h4g4", "g6f6", "h2h4", "h1a1", "e3d2", "a1a2",
                "e2e1", "a2a3", "h4h5", "a3a1", "e1e2", "a1a2", "e2d1", "a2a1",
                "d1e2", "a1a2"
        };
        HistoryPosition position = play(moves);
        int priorRootMatches = 0;
        for (int i = 0; i < position.keys.length - 1; i++) {
            if (position.keys[i] == position.board.zobristKey) priorRootMatches++;
        }
        check(priorRootMatches == 1,
                "Game 1 root after 53...Ra2 must be a twofold, got prior matches=" + priorRootMatches);

        SearchResult result = search(position.board, position.keys,
                new TranspositionTable(16), 10);
        String bestMove = Move.toUci(result.move);
        check(!bestMove.equals("e2d1"),
                "Game 1 root should reject the Kd1 cycle; got " + bestMove
                        + " at score " + result.score);
        check(result.score > 0,
                "Game 1 root should preserve its winning evaluation; got " + result.score);
    }

    private static void testQuiescenceTwofoldSearchCycleIsDraw() {
        try {
            Method method = Search.class.getDeclaredMethod(
                    "quiescence", Board.class, int.class, int.class, int.class);
            method.setAccessible(true);
            Board cycleBoard = board("7k/8/8/8/8/8/8/3Q3K w - - 0 1");
            Search cycleSearch = new Search(new TranspositionTable(1));
            setSearchHistory(cycleSearch, 1, new long[] {cycleBoard.zobristKey, 0L, 0L});
            int cycleScore = (Integer) method.invoke(cycleSearch, cycleBoard,
                    -Evaluator.INFINITY_SCORE, Evaluator.INFINITY_SCORE, 1);
            check(cycleScore == 0,
                    "qsearch must score a twofold search cycle as a draw; got " + cycleScore);

            Board winningBoard = board("7k/8/8/8/8/8/8/3Q3K w - - 0 1");
            Search rootTwofoldSearch = new Search(new TranspositionTable(1));
            setSearchHistory(rootTwofoldSearch, 2,
                    new long[] {winningBoard.zobristKey, 0L, 0L});
            int rootTwofoldScore = (Integer) method.invoke(rootTwofoldSearch, winningBoard,
                    -Evaluator.INFINITY_SCORE, Evaluator.INFINITY_SCORE, 0);
            check(rootTwofoldScore > 0,
                    "root qsearch must not treat a twofold as terminal; got " + rootTwofoldScore);

            Search rootThirdfoldSearch = new Search(new TranspositionTable(1));
            setSearchHistory(rootThirdfoldSearch, 4,
                    new long[] {winningBoard.zobristKey, 0L, winningBoard.zobristKey, 0L, 0L});
            int rootThirdfoldScore = (Integer) method.invoke(rootThirdfoldSearch, winningBoard,
                    -Evaluator.INFINITY_SCORE, Evaluator.INFINITY_SCORE, 0);
            check(rootThirdfoldScore == 0,
                    "root qsearch must recognize a genuine third occurrence; got " + rootThirdfoldScore);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not exercise qsearch repetition handling", e);
        }
    }

    private static void setSearchHistory(Search search, int base, long[] keys)
            throws ReflectiveOperationException {
        Field historyBase = Search.class.getDeclaredField("historyBase");
        Field keyStack = Search.class.getDeclaredField("keyStack");
        historyBase.setAccessible(true);
        keyStack.setAccessible(true);
        historyBase.setInt(search, base);
        keyStack.set(search, Arrays.copyOf(keys, base + Search.MAX_PLY + 8));
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
                TranspositionTable.EXACT, drawingMove, position.board.halfmoveClock); // earlier root
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

    /** Regression for Lichess game qJGISQlH, after 43.Qxf7+. */
    private static void testIllegalRootTranspositionMoveCannotBecomeBestMove() {
        Board board = board("8/5Qp1/pR5q/3pPk2/3P1P1p/6PP/8/1b4K1 b - - 0 43");
        TranspositionTable tt = new TranspositionTable(1);
        Search search = new Search(tt);
        SearchResult result = new SearchResult();
        int illegalMove = Move.encode(48, 53, Move.QUIET); // a7f7; a7 is empty.
        search.setInfoListener((depth, selDepth, score, mate, mateIn,
                                nodes, nps, timeMs, pv) -> {
            if (depth == 1) {
                // Plant a same-generation exact root entry between iterative
                // deepening iterations, as could happen after TT corruption.
                tt.store(board.zobristKey, 2, score,
                        TranspositionTable.EXACT, illegalMove);
            }
            if (depth == 2) {
                result.pv = pv;
            }
        });

        result.move = search.search(board, 2, -1, null);
        String bestMove = Move.toUci(result.move);
        check(isLegalMove(board, result.move),
                "search must return a legal move after rejecting stale root TT data; got "
                        + bestMove);
        check(!bestMove.equals("a7f7"),
                "illegal root TT move a7f7 must not become bestmove");
        check(result.pv != null && result.pv.startsWith(bestMove),
                "reported PV must begin with a legal bestmove; bestmove=" + bestMove
                        + ", pv=" + result.pv);
        check(!result.pv.contains("a7f7"),
                "reported PV must exclude the stale illegal move; pv=" + result.pv);
    }

    private static void testPvStopsAtStaleOrNonExactTtEntries() {
        Board root = new Board();
        int firstMove = findLegalMove(root, "e2e4");
        Board child = board(root.toFen());
        child.makeMove(firstMove);
        int reply = findLegalMove(child, "a7a6");
        TranspositionTable tt = new TranspositionTable(1);
        Search search = new Search(tt);
        tt.newSearch();
        tt.store(child.zobristKey, 2, 0, TranspositionTable.UPPER_BOUND, reply);
        check(extractPv(search, root, 4, firstMove).equals("e2e4"),
                "PV extraction must stop before a bound TT move");

        tt.store(child.zobristKey, 2, 0, TranspositionTable.EXACT, reply);
        tt.newSearch(); // entry is now stale for this top-level search
        check(extractPv(search, root, 4, firstMove).equals("e2e4"),
                "PV extraction must stop before a stale-generation TT move");
    }

    private static String extractPv(Search search, Board board, int length, int rootMove) {
        try {
            Method method = Search.class.getDeclaredMethod(
                    "extractPv", Board.class, int.class, int.class);
            method.setAccessible(true);
            return (String) method.invoke(search, board, length, rootMove);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not test TT PV filtering", e);
        }
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

    private static boolean isLegalMove(Board board, int candidate) {
        MoveList moves = new MoveList();
        MoveGenerator.generateLegal(board, moves);
        for (int i = 0; i < moves.size; i++) if (moves.get(i) == candidate) return true;
        return false;
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

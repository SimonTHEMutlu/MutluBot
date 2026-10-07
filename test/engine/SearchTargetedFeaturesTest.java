package engine;

/** Regressions for the root forced-move shortcut and bounded pawn-offer LMR guard. */
public final class SearchTargetedFeaturesTest {
    public static void main(String[] args) {
        testForcedQuietCheckEvasion();
        testNoLegalMoveResults();
        testReusedSearchResetsTerminalSelectiveDepth();
        testQueenGambitOfferGuard();
        testUnsoundPawnOfferIsNotChosen();
        testUciNoMoveFormatting();
        System.out.println("SearchTargetedFeaturesTest passed");
    }

    private static void testForcedQuietCheckEvasion() {
        Board board = board("4kb2/2Q5/6K1/8/8/8/8/4RR2 b - - 0 1");
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        check(board.isInCheck(board.sideToMove), "forced-move fixture must be check");
        check(legal.size == 1 && Move.toUci(legal.get(0)).equals("f8e7"),
                "fixture must have exactly one quiet evasion: " + legalMoves(legal));

        String before = board.toFen();
        Search search = new Search(new TranspositionTable(1));
        search.setInstrumentationEnabled(true);
        long started = System.nanoTime();
        int best = search.search(board, 20, 0, null);
        long elapsed = System.nanoTime() - started;
        check(best == legal.get(0), "forced quiet evasion must be returned immediately");
        check(isLegal(board, best), "forced shortcut must return a legal move");
        check(before.equals(board.toFen()), "forced shortcut must preserve root board state");
        check(search.getLastStats().totalNodes == 0,
                "forced move shortcut must not enter the normal search");
        check(elapsed < 100_000_000L, "forced move shortcut should have negligible latency");
    }

    private static void testNoLegalMoveResults() {
        Board mate = board("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1");
        Board stale = board("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
        for (Board board : new Board[] {mate, stale}) {
            MoveList legal = new MoveList();
            MoveGenerator.generateLegal(board, legal);
            check(legal.size == 0, "terminal fixture must have no legal moves");
            Search search = new Search(new TranspositionTable(1));
            search.setInstrumentationEnabled(true);
            check(search.search(board, 12, -1, new long[] {board.zobristKey}) == Move.NONE,
                    "checkmate and stalemate must return Move.NONE");
            check(search.getLastStats().totalNodes == 0,
                    "terminal root must return without entering search");
        }
    }

    private static void testReusedSearchResetsTerminalSelectiveDepth() {
        Search search = new Search(new TranspositionTable(1));
        final int[] reportedSelectiveDepth = {-1};
        search.setInfoListener((depth, selDepth, score, mate, mateIn, nodes, nps, timeMs, pv) ->
                reportedSelectiveDepth[0] = selDepth);
        search.search(new Board(), 1, -1, null);
        check(reportedSelectiveDepth[0] > 0,
                "nonterminal search should establish a positive selective depth");

        Board mate = board("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1");
        search.search(mate, 12, -1, null);
        check(reportedSelectiveDepth[0] == 0,
                "terminal info after reusing Search must report selective depth zero");
    }

    private static void testQueenGambitOfferGuard() {
        Board board = board("rnbqkbnr/ppp1pppp/8/3p4/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 0 2");
        int c4 = find(board, "c2c4");
        board.makeMove(c4);
        Search search = new Search(new TranspositionTable(1));
        check(search.isNearRootPawnOffer(board, c4, Piece.WHITE, 0),
                "Queen's Gambit c4 must be recognized as a root pawn offer to d5");
        check(search.isNearRootPawnOffer(board, c4, Piece.WHITE, 1),
                "pawn offers on the second search ply must also be protected");
        check(!search.isNearRootPawnOffer(board, c4, Piece.WHITE, 2),
                "pawn-offer protection must stop after two plies");
        check(search.lmrReductionForMove(c4, 16, 20, 0, Move.NONE,
                        false, false, false, board, Piece.WHITE) == 0,
                "a late Queen's Gambit c4 move must receive nominal-depth verification");
        check(search.lmrReductionForMove(c4, 16, 20, 2, Move.NONE,
                        false, false, false, board, Piece.WHITE) > 0,
                "the pawn-offer LMR exemption must remain strictly near-root");

        Board reply = new Board();
        reply.setFromFen("rnbqkbnr/ppp1pppp/8/3p4/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 0 2");
        int a3 = find(reply, "a2a3");
        reply.makeMove(a3);
        check(!search.isNearRootPawnOffer(reply, a3, Piece.WHITE, 0),
                "ordinary pawn moves not capturable by an enemy pawn must not be exempt");
    }

    private static void testUnsoundPawnOfferIsNotChosen() {
        Board board = board("4k3/8/8/8/3p4/8/4P3/4K3 w - - 0 1");
        int offer = find(board, "e2e3");
        Board afterOffer = board(board.toFen());
        afterOffer.makeMove(offer);
        Search search = new Search(new TranspositionTable(1));
        check(search.isNearRootPawnOffer(afterOffer, offer, Piece.WHITE, 0),
                "an unsound hanging pawn must receive the same bounded full-depth verification");
        int best = search.search(board, 5, -1, null);
        check(best != offer,
                "full-depth verification must reject an unrecapturable pawn hang");
        check(isLegal(board, best), "search must return a legal alternative to the pawn hang");
    }

    private static void testUciNoMoveFormatting() {
        check(UCIEngine.formatBestMove(Move.NONE).equals("0000"),
                "UCI must format no legal move as 0000");
        check(UCIEngine.formatBestMove(Move.encode(12, 28, Move.DOUBLE_PAWN_PUSH)).equals("e2e4"),
                "UCI move formatting must preserve legal move notation");
    }

    private static Board board(String fen) {
        Board board = new Board();
        board.setFromFen(fen);
        return board;
    }

    private static int find(Board board, String uci) {
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        for (int i = 0; i < legal.size; i++) {
            int move = legal.get(i);
            if (Move.toUci(move).equals(uci)) return move;
        }
        throw new AssertionError("missing legal move " + uci + " in " + board.toFen());
    }

    private static boolean isLegal(Board board, int move) {
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        for (int i = 0; i < legal.size; i++) if (legal.get(i) == move) return true;
        return false;
    }

    private static String legalMoves(MoveList moves) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < moves.size; i++) {
            if (i > 0) result.append(' ');
            result.append(Move.toUci(moves.get(i)));
        }
        return result.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

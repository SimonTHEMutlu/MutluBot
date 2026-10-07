package engine;

import java.util.ArrayList;
import java.util.List;

/** Focused tests for the opt-in, bounded root prophylaxis experiment. */
public final class SearchProphylaxisProbeTest {
    public static void main(String[] args) {
        testEnabledProbeIsBoundedAndRestoresBoard();
        testProbeDoesNotChangeNormalSearchWorkOrCandidateScores();
        testForcingAlternativesDoNotDisableQuietCandidateProbes();
        testGameSevenCheckingPlanIncludesEvasionAndContinuation();
        testInterruptedProbeRestoresBoardAndLeavesTtUntouched();
        testInterruptedRootReturnsLastCompletedMove();
        testCheckedAndQueenlessRootsDoNotTrigger();
        System.out.println("SearchProphylaxisProbeTest passed");
    }

    private static void testEnabledProbeIsBoundedAndRestoresBoard() {
        Board board = new Board();
        String beforeFen = board.toFen();
        long beforeKey = board.zobristKey;
        long[][] beforePieces = copy(board.pieceBB);
        long[] beforeOccupancy = board.occupancy.clone();
        byte[] beforeMailbox = board.mailbox.clone();
        Search search = new Search(new TranspositionTable(1));
        search.setProphylaxisProbeEnabled(true);
        search.setInstrumentationEnabled(true);
        final String[] depthFourInfo = {null};
        search.setInfoListener((depth, selDepth, score, mate, mateIn, nodes, nps, timeMs, pv) -> {
            if (depth == 4) depthFourInfo[0] = score + "|" + pv;
        });

        int move = search.search(board, 4, -1, null);
        Search.ProphylaxisDiagnostics diagnostics = search.getLastProphylaxisDiagnostics();

        check(move != Move.NONE, "search must return a legal move");
        check(diagnostics != null, "quiet queen middlegame/start position should be probed");
        check(diagnostics.candidatesProbed > 0
                        && diagnostics.candidatesProbed <= 8,
                "probe should inspect only its fixed-capacity candidate buffer");
        check(diagnostics.leafEvaluations > 0 && diagnostics.leafEvaluations <= 512,
                "probe must respect the shared 512 static-leaf cap");
        check(diagnostics.selectedAdjustmentCp >= -12
                        && diagnostics.selectedAdjustmentCp <= 12,
                "prophylaxis adjustment must be capped at 12cp");
        check(diagnostics.selectedMove != null && diagnostics.selectedMove.length() == 4,
                "diagnostics should identify the selected root move");
        check(depthFourInfo[0] != null,
                "UCI information should be produced for the completed iteration");
        String[] infoParts = depthFourInfo[0].split("\\|", 2);
        check(infoParts.length == 2 && infoParts[1].startsWith(diagnostics.selectedMove),
                "reported PV must start with the selected root move");
        check(infoParts[0].equals(Integer.toString(diagnostics.selectedNormalScore)),
                "reported score must match the selected move's unadjusted score");
        check(Search.formatThreatMetrics(diagnostics.baselineThreatMetrics)
                        .contains("safeChecks="),
                "diagnostics should include safe checks, king pressure, pawn breaks, and files");
        assertBoardState(board, beforeFen, beforeKey, beforePieces,
                beforeOccupancy, beforeMailbox);
    }

    private static void testProbeDoesNotChangeNormalSearchWorkOrCandidateScores() {
        Board baselineBoard = new Board();
        Search baseline = new Search(new TranspositionTable(1));
        baseline.setInstrumentationEnabled(true);
        List<String> baselineCandidates = new ArrayList<>();
        baseline.setRootCandidateListener((depth, rank, move, score, exact, nodes, pv) -> {
            if (depth == 3) baselineCandidates.add(rank + "|" + move + "|" + score + "|" + exact);
        });
        baseline.search(baselineBoard, 3, -1, null);

        Board probedBoard = new Board();
        Search probed = new Search(new TranspositionTable(1));
        probed.setInstrumentationEnabled(true);
        probed.setProphylaxisProbeEnabled(true);
        List<String> probedCandidates = new ArrayList<>();
        probed.setRootCandidateListener((depth, rank, move, score, exact, nodes, pv) -> {
            if (depth == 3) probedCandidates.add(rank + "|" + move + "|" + score + "|" + exact);
        });
        probed.search(probedBoard, 3, -1, null);

        check(baselineCandidates.equals(probedCandidates),
                "static threat probing must not alter normal root candidate scores or order");
        Search.ProphylaxisDiagnostics diagnostics = probed.getLastProphylaxisDiagnostics();
        long extraNodes = probed.getLastStats().totalNodes - baseline.getLastStats().totalNodes;
        check(extraNodes >= 0, "probe-enabled search must not remove normal search work");
        check(diagnostics != null && diagnostics.verificationNodes == extraNodes,
                "any extra normal nodes must be reported as exact verification work");
    }

    private static void testCheckedAndQueenlessRootsDoNotTrigger() {
        Board checked = new Board();
        checked.setFromFen("q3k3/8/8/8/8/8/4r3/3QK3 w - - 0 1");
        Search checkedSearch = new Search(new TranspositionTable(1));
        checkedSearch.setProphylaxisProbeEnabled(true);
        checkedSearch.search(checked, 4, -1, null);
        check(checkedSearch.getLastProphylaxisDiagnostics() == null,
                "a checked root must not run prophylaxis probing");

        Board queenless = new Board();
        queenless.setFromFen("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1");
        Search queenlessSearch = new Search(new TranspositionTable(1));
        queenlessSearch.setProphylaxisProbeEnabled(true);
        queenlessSearch.search(queenless, 4, -1, null);
        check(queenlessSearch.getLastProphylaxisDiagnostics() == null,
                "queenless roots must not run prophylaxis probing");
    }

    private static void testForcingAlternativesDoNotDisableQuietCandidateProbes() {
        Board board = new Board();
        board.setFromFen("3q2k1/8/8/3p4/2P5/8/8/2RQ2K1 w - - 0 1");
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        boolean hasCapture = false;
        boolean hasCheck = false;
        for (int i = 0; i < legal.size; i++) {
            int move = legal.get(i);
            hasCapture |= Move.isCapture(move);
            board.makeMove(move);
            hasCheck |= board.isInCheck(board.sideToMove);
            board.unmakeMove();
        }
        check(hasCapture && hasCheck,
                "test root must contain both a capture and a checking alternative");

        Search search = new Search(new TranspositionTable(1));
        search.setProphylaxisProbeEnabled(true);
        final java.util.List<String> probedMoves = new java.util.ArrayList<>();
        search.setProphylaxisCandidateListener((depth, move, score, threatScore,
                adjustment, metrics, line, leaves) -> probedMoves.add(move));
        search.search(board, 4, -1, null);

        Search.ProphylaxisDiagnostics diagnostics = search.getLastProphylaxisDiagnostics();
        check(diagnostics != null && diagnostics.candidatesProbed > 0,
                "irrelevant forcing alternatives must not disable all quiet probes");
        check(probedMoves.contains("c4d5"),
                "a nonchecking capture may be included as an exact candidate");
        check(!probedMoves.contains("d1g4"),
                "a checking root candidate must not be probed");
        for (String move : probedMoves) {
            int encoded = findLegal(board, move);
            board.makeMove(encoded);
            boolean givesCheck = board.isInCheck(board.sideToMove);
            board.unmakeMove();
            check(!givesCheck, "checking candidates must not be probed");
        }
    }

    private static int findLegal(Board board, String uci) {
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        for (int i = 0; i < legal.size; i++) {
            if (Move.toUci(legal.get(i)).equals(uci)) return legal.get(i);
        }
        throw new AssertionError("expected legal candidate " + uci);
    }

    private static void testGameSevenCheckingPlanIncludesEvasionAndContinuation() {
        Board board = new Board();
        board.setFromFen("r2r3k/ppp3p1/3P2p1/2P1p1b1/2Q5/1P1P4/PB1Nq1B1/R5K1 w - - 1 28");
        int advance = findLegal(board, "d6c7");
        board.makeMove(advance);
        String afterCandidateFen = board.toFen();
        long afterCandidateKey = board.zobristKey;
        java.util.List<String> plans = new java.util.ArrayList<>();
        Search search = new Search(new TranspositionTable(1));
        search.setProphylaxisPlanListener((line, score) -> plans.add(line));

        Search.ThreatProbeResult result = search.probeOpponentTwoActionsForTesting(
                board, Piece.WHITE, 400);

        check(result.checkedThreats > 0,
                "Game 7 plan probe should analyze checking first moves");
        boolean sawBishopCheckWithEvasionAndContinuation = false;
        boolean sawExpectedQueenContinuation = false;
        for (String line : plans) {
            String[] parts = line.split(" ");
            if (parts.length == 4 && parts[0].equals("CHECK")
                    && parts[1].equals("g5e3")) {
                sawBishopCheckWithEvasionAndContinuation = true;
                if (parts[3].equals("e2h5")) sawExpectedQueenContinuation = true;
            }
        }
        check(sawBishopCheckWithEvasionAndContinuation,
                "the Game 7 bishop check should be followed through a legal evasion and reply");
        check(sawExpectedQueenContinuation,
                "the Game 7 plan should include ...Qh5+ after a legal check evasion");
        check(board.toFen().equals(afterCandidateFen)
                        && board.zobristKey == afterCandidateKey,
                "Game 7 check/evasion probe must restore the complete position");
        board.unmakeMove();
    }

    private static void testInterruptedProbeRestoresBoardAndLeavesTtUntouched() {
        Board board = new Board();
        board.setFromFen("r2r3k/ppp3p1/3P2p1/2P1p1b1/2Q5/1P1P4/PB1Nq1B1/R5K1 w - - 1 28");
        board.makeMove(findLegal(board, "d6c7"));
        String beforeFen = board.toFen();
        long beforeKey = board.zobristKey;
        long[][] beforePieces = copy(board.pieceBB);
        long[] beforeOccupancy = board.occupancy.clone();
        byte[] beforeMailbox = board.mailbox.clone();

        TranspositionTable tt = new TranspositionTable(1);
        tt.store(beforeKey, 9, 45, TranspositionTable.EXACT, Move.encode(0, 1, Move.QUIET));
        long ttEntry = tt.probePacked(beforeKey);
        Search search = new Search(tt);
        final int[] plans = {0};
        search.setProphylaxisPlanListener((line, score) -> {
            plans[0]++;
            search.requestStop();
        });
        search.probeOpponentTwoActionsForTesting(board, Piece.WHITE, 512);

        check(plans[0] > 0, "interruption hook must run inside a checking-plan probe");
        assertBoardState(board, beforeFen, beforeKey, beforePieces,
                beforeOccupancy, beforeMailbox);
        check(tt.probePacked(beforeKey) == ttEntry,
                "hypothetical threat probing must not read or overwrite TT state");
        board.unmakeMove();
        check(board.toFen().equals("r2r3k/ppp3p1/3P2p1/2P1p1b1/2Q5/1P1P4/PB1Nq1B1/R5K1 w - - 1 28"),
                "interrupted speculative probing must leave the undo stack usable");
    }

    private static void testInterruptedRootReturnsLastCompletedMove() {
        Board board = new Board();
        String beforeFen = board.toFen();
        long beforeKey = board.zobristKey;
        Search search = new Search(new TranspositionTable(1));
        search.setProphylaxisProbeEnabled(true);
        final String[] completedMove = {null};
        search.setInfoListener((depth, selDepth, score, mate, mateIn, nodes, nps, timeMs, pv) -> {
            if (!pv.isEmpty()) completedMove[0] = pv.split(" ")[0];
        });
        search.setProphylaxisPlanListener((line, score) -> search.requestStop());

        int result = search.search(board, 8, -1, null);

        check(completedMove[0] != null
                        && Move.toUci(result).equals(completedMove[0]),
                "an interrupted probe must return the last completed normal iteration move");
        check(board.toFen().equals(beforeFen) && board.zobristKey == beforeKey,
                "an interrupted root probe must restore the root position");
    }

    private static long[][] copy(long[][] source) {
        long[][] result = new long[source.length][];
        for (int i = 0; i < source.length; i++) result[i] = source[i].clone();
        return result;
    }

    private static void assertBoardState(Board board, String fen, long key,
                                         long[][] pieces, long[] occupancy,
                                         byte[] mailbox) {
        check(fen.equals(board.toFen()), "board FEN/state fields must be restored");
        check(board.zobristKey == key, "Zobrist key must be restored");
        check(board.allOccupancy == (occupancy[0] | occupancy[1]),
                "combined occupancy must be consistent after probing");
        for (int i = 0; i < pieces.length; i++) {
            check(java.util.Arrays.equals(pieces[i], board.pieceBB[i]),
                    "piece bitboards must be restored");
        }
        check(java.util.Arrays.equals(occupancy, board.occupancy),
                "color occupancy must be restored");
        check(java.util.Arrays.equals(mailbox, board.mailbox),
                "mailbox must be restored");

        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        int first = legal.get(0);
        board.makeMove(first);
        board.unmakeMove();
        check(fen.equals(board.toFen()) && board.zobristKey == key,
                "move history must remain usable after all speculative passes");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

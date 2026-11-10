package engine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Deterministic guard, bound, legality, and instrumentation checks for Issue 5.1 RFP. */
public final class SearchRfpTest {
    private static final String ADVANTAGE_FEN =
            "4k3/ppp2ppp/2n5/8/3Q4/8/PPP2PPP/4K2R w - - 0 1";

    public static void main(String[] args) throws Exception {
        testMarginLadderAndExactBoundary();
        testBothColorsAndAboveMargin();
        testDepthBuckets();
        testEligibilityGuards();
        testActualNegamaxGuardExits();
        testTtBoundsPrecedeRfp();
        testStalemateAndMateRemainTerminal();
        testHistoryAndFiftyMovePrecedence();
        testCutoffRestoresBoardAndSearchHistory();
        testInstrumentationOffAndCountersReset();
        testPublicSearchReturnsLegalMove();
        System.out.println("SearchRfpTest passed");
    }

    private static void testMarginLadderAndExactBoundary() throws Exception {
        Board probe = board(ADVANTAGE_FEN);
        int staticEval = Evaluator.evaluate(probe);
        check(staticEval > 300, "fixture must have a useful side-to-move advantage");
        for (int marginPerPly : new int[] {100, 125, 150}) {
            int beta = staticEval - marginPerPly;
            Search search = search(true, true, marginPerPly);
            String before = probe.toFen();
            int score = invokeNegamax(search, probe, 1, beta - 1, beta, 1, true);
            Search.SearchStats stats = captureStats(search);
            check(score == beta, "RFP must return the fail-hard beta bound at equality");
            check(stats.rfpEligibilityChecks == 1 && stats.rfpEligibleNodes == 1,
                    "eligible depth-1 node must be counted once");
            check(stats.rfpStaticEvalCalls == 1 && stats.evaluatorCalls == 1,
                    "RFP static evaluation must be counted in both counter families");
            check(stats.rfpCutoffs == 1 && stats.rfpEligibleDepth1 == 1
                            && stats.rfpCutoffsDepth1 == 1,
                    "depth-1 RFP eligibility and cutoff counters must reconcile");
            check(stats.rfpEligibleDepth2 == 0 && stats.rfpEligibleDepth3 == 0
                            && stats.rfpCutoffsDepth2 == 0 && stats.rfpCutoffsDepth3 == 0,
                    "depth counters must not leak across buckets");
            check(before.equals(probe.toFen()), "RFP cutoff must restore the board");
        }

        Search below = search(true, true, 125);
        int margin = 125;
        int beta = staticEval - margin + 1;
        invokeNegamax(below, board(ADVANTAGE_FEN), 1, beta - 1, beta, 1, true);
        Search.SearchStats belowStats = captureStats(below);
        check(belowStats.rfpEligibleNodes == 1 && belowStats.rfpCutoffs == 0,
                "one centipawn below the threshold must search moves instead of pruning");
        check(belowStats.legalMovesSearched > 0,
                "a failed RFP threshold must continue into legal move search");

        Search above = search(true, true, 125);
        int aboveBeta = staticEval - margin - 1;
        int aboveScore = invokeNegamax(above, board(ADVANTAGE_FEN), 1,
                aboveBeta - 1, aboveBeta, 1, true);
        check(aboveScore == aboveBeta && captureStats(above).rfpCutoffs == 1,
                "one centipawn above the threshold must take the fail-hard cutoff");

        boolean rejected = false;
        try {
            search(true, true, 99);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "unpredeclared margin values must be rejected");
    }

    private static void testBothColorsAndAboveMargin() throws Exception {
        String[] colors = {
                ADVANTAGE_FEN,
                "4k2r/ppp2ppp/8/3q4/8/2N5/PPP2PPP/4K3 b - - 0 1"
        };
        for (String fen : colors) {
            Board position = board(fen);
            int staticEval = Evaluator.evaluate(position);
            check(staticEval > 300,
                    "color-mirrored fixture must have a positive side-to-move evaluation");
            int beta = staticEval - 125;
            Search search = search(true, true, 125);
            int score = invokeNegamax(search, position, 1, beta - 1, beta, 1, true);
            Search.SearchStats stats = captureStats(search);
            check(score == beta && stats.rfpCutoffs == 1,
                    "RFP must honor the equality boundary for either side to move");
        }
    }

    private static void testEligibilityGuards() {
        Search search = search(true, true, 125);
        Board rich = board(ADVANTAGE_FEN);
        check(eligible(search, rich, 2, 1, false, false, true, false, 0, 1),
                "ordinary internal non-PV node should be eligible");
        check(!eligible(search, rich, 2, 0, false, false, true, false, 0, 1),
                "root must be excluded");
        check(!eligible(search, rich, 2, 1, true, false, true, false, 0, 1),
                "incoming PV node must remain excluded after TT window changes");
        check(!eligible(search, rich, 2, 1, false, true, true, false, 0, 1),
                "check and check-evasion nodes must be excluded");
        check(!eligible(search, rich, 2, 1, false, false, false, false, 0, 1),
                "a node reached directly through a null move must be excluded");
        check(!eligible(search, rich, 2, 1, false, false, true, true, 0, 1),
                "repetition-sensitive nodes must be excluded");
        check(!eligible(search, rich, 0, 1, false, false, true, false, 0, 1),
                "quiescence depth must be excluded");
        check(!eligible(search, rich, 4, 1, false, false, true, false, 0, 1),
                "depth greater than three must be excluded");
        check(!eligible(search, rich, 2, 1, false, false, true, false, 1, 1),
                "invalid or closed windows must be excluded");
        check(!eligible(search, rich, 2, 1, false, false, true, false,
                        0, Evaluator.MATE_SCORE - Search.MAX_PLY),
                "mate-score windows must be excluded");

        Board nearFifty = board("4k3/ppp2ppp/2n5/8/3Q4/8/PPP2PPP/4K2R w - - 97 50");
        check(!eligible(search, nearFifty, 3, 1, false, false, true, false, 0, 1),
                "a horizon that reaches the 100-halfmove draw must be excluded");

        Board pawnEnding = board("8/8/8/8/8/4k3/4p3/4K3 w - - 0 1");
        check(!eligible(search, pawnEnding, 2, 1, false, false, true, false, 0, 1),
                "pawn-only zugzwang-risk material must be excluded");
        Board loneMinor = board("4r1k1/8/8/8/8/8/4N3/4K3 w - - 0 1");
        check(!eligible(search, loneMinor, 2, 1, false, false, true, false, 0, 1),
                "a lone minor against non-pawn material must be excluded");
    }

    private static void testActualNegamaxGuardExits() throws Exception {
        Board mate = board("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1");
        Search inCheck = search(true, true, 125);
        int mateScore = invokeNegamax(inCheck, mate, 1, 0, 1, 1, true);
        Search.SearchStats inCheckStats = captureStats(inCheck);
        check(mateScore < -Evaluator.MATE_SCORE + Search.MAX_PLY,
                "checkmate fixture must return a mate score");
        check(inCheckStats.rfpEligibilityChecks == 1
                        && inCheckStats.rfpEligibleNodes == 0
                        && inCheckStats.rfpStaticEvalCalls == 0
                        && inCheckStats.evaluatorCalls == 0,
                "actual in-check mate node must skip RFP and all static evaluation");

        Search stopped = search(true, true, 125);
        stopped.requestStop();
        int stoppedScore = invokeNegamax(stopped, board(ADVANTAGE_FEN),
                1, 0, 1, 1, true);
        Search.SearchStats stoppedStats = captureStats(stopped);
        check(stoppedScore == 0 && stoppedStats.rfpEligibilityChecks == 0
                        && stoppedStats.rfpStaticEvalCalls == 0
                        && stoppedStats.evaluatorCalls == 0,
                "pre-stopped negamax must exit before RFP or evaluation");

        Board stale = board("7k/8/5KQ1/8/8/8/8/8 b - - 0 1");
        Search material = search(true, true, 125);
        check(invokeNegamax(material, stale, 1, 0, 1, 1, true) == 0,
                "stalemate material-gate fixture must remain a draw");
        Search.SearchStats materialStats = captureStats(material);
        check(materialStats.rfpEligibilityChecks == 1
                        && materialStats.rfpEligibleNodes == 0
                        && materialStats.rfpStaticEvalCalls == 0
                        && materialStats.evaluatorCalls == 0,
                "actual low-material node must be rejected before evaluation");

        // Nodes that continue searching may perform ordinary descendant
        // evaluations; these assertions specifically ensure no RFP evaluation
        // occurs at the guarded node itself.
        assertNegamaxGuardWithoutRfpEval(0, -1, 1, true,
                "root node");
        assertNegamaxGuardWithoutRfpEval(1, -10, 10, true,
                "PV node");
        assertNegamaxGuardWithoutRfpEval(1, 0, 1, false,
                "null-move child");
        assertNegamaxGuardWithoutRfpEval(1, 0, 1, true, 4,
                "depth-four node");
        assertNegamaxGuardWithoutRfpEval(1, -Evaluator.MATE_SCORE,
                Evaluator.MATE_SCORE, true, 1,
                "mate-window node");
    }

    private static void assertNegamaxGuardWithoutRfpEval(int ply, int alpha, int beta,
                                                         boolean nullOk, String label)
            throws Exception {
        assertNegamaxGuardWithoutRfpEval(ply, alpha, beta, nullOk, 1, label);
    }

    private static void assertNegamaxGuardWithoutRfpEval(int ply, int alpha, int beta,
                                                         boolean nullOk, int depth,
                                                         String label) throws Exception {
        Search search = search(true, true, 125);
        invokeNegamax(search, board(ADVANTAGE_FEN), depth,
                alpha, beta, ply, nullOk);
        Search.SearchStats stats = captureStats(search);
        check(stats.rfpEligibilityChecks >= 1 && stats.rfpEligibleNodes == 0
                        && stats.rfpStaticEvalCalls == 0,
                label + " must be rejected by actual negamax before RFP evaluation; checks="
                        + stats.rfpEligibilityChecks + " eligible=" + stats.rfpEligibleNodes
                        + " evals=" + stats.rfpStaticEvalCalls);
    }

    private static void testDepthBuckets() throws Exception {
        int staticEval = Evaluator.evaluate(board(ADVANTAGE_FEN));
        for (int depth = 1; depth <= 3; depth++) {
            Search search = search(true, true, 125);
            int beta = staticEval - depth * 125;
            invokeNegamax(search, board(ADVANTAGE_FEN), depth,
                    beta - 1, beta, 1, true);
            Search.SearchStats stats = captureStats(search);
            check(stats.rfpEligibleNodes == 1 && stats.rfpCutoffs == 1,
                    "RFP must activate at each supported depth");
            check(stats.rfpEligibleDepth1 == (depth == 1 ? 1 : 0)
                            && stats.rfpEligibleDepth2 == (depth == 2 ? 1 : 0)
                            && stats.rfpEligibleDepth3 == (depth == 3 ? 1 : 0),
                    "eligible counts must use the matching depth bucket");
            check(stats.rfpCutoffsDepth1 == (depth == 1 ? 1 : 0)
                            && stats.rfpCutoffsDepth2 == (depth == 2 ? 1 : 0)
                            && stats.rfpCutoffsDepth3 == (depth == 3 ? 1 : 0),
                    "cutoff counts must use the matching depth bucket");
        }
    }

    private static void testTtBoundsPrecedeRfp() throws Exception {
        Board exactBoard = board(ADVANTAGE_FEN);
        TranspositionTable exactTt = new TranspositionTable(1);
        exactTt.store(exactBoard.zobristKey, 3, 777, TranspositionTable.EXACT, Move.NONE);
        Search exactSearch = new Search(exactTt);
        exactSearch.setInstrumentationEnabled(true);
        exactSearch.setReverseFutilityEnabledForTesting(true);
        int exact = invokeNegamax(exactSearch, exactBoard, 1, 0, 1, 1, true);
        Search.SearchStats exactStats = captureStats(exactSearch);
        check(exact == 777 && exactStats.ttExactHits == 1,
                "usable exact TT entry must retain precedence over RFP");
        check(exactStats.rfpEligibilityChecks == 0 && exactStats.rfpCutoffs == 0,
                "TT exact hit must exit before RFP work");

        Board lowerBoard = board(ADVANTAGE_FEN);
        TranspositionTable lowerTt = new TranspositionTable(1);
        lowerTt.store(lowerBoard.zobristKey, 3, 1,
                TranspositionTable.LOWER_BOUND, Move.NONE);
        Search lowerSearch = new Search(lowerTt);
        lowerSearch.setInstrumentationEnabled(true);
        lowerSearch.setReverseFutilityEnabledForTesting(true);
        int lower = invokeNegamax(lowerSearch, lowerBoard, 1, 0, 1, 1, true);
        Search.SearchStats lowerStats = captureStats(lowerSearch);
        check(lower == 1 && lowerStats.ttBoundCutoffs == 1,
                "TT lower bound reaching beta must retain its cutoff");
        check(lowerStats.rfpEligibilityChecks == 0 && lowerStats.rfpCutoffs == 0,
                "TT bound cutoff must exit before RFP work");

        Board upperBoard = board(ADVANTAGE_FEN);
        int staticEval = Evaluator.evaluate(upperBoard);
        int originalBeta = staticEval - 100;
        int narrowedBeta = originalBeta - 10;
        TranspositionTable upperTt = new TranspositionTable(1);
        upperTt.store(upperBoard.zobristKey, 3, narrowedBeta,
                TranspositionTable.UPPER_BOUND, Move.NONE);
        Search upperSearch = new Search(upperTt);
        upperSearch.setInstrumentationEnabled(true);
        upperSearch.setReverseFutilityEnabledForTesting(true);
        int upper = invokeNegamax(upperSearch, upperBoard, 1,
                narrowedBeta - 100, originalBeta, 1, true);
        Search.SearchStats upperStats = captureStats(upperSearch);
        check(upperStats.ttHits == 1 && upperStats.ttBoundCutoffs == 0,
                "adequate-depth current-generation TT upper bound must narrow beta without cutting off");
        check(upperStats.rfpStaticEvalCalls == 0 && upperStats.rfpCutoffs == 0,
                "RFP must skip evaluation and cutoff after TT upper-bound beta narrowing");
        check(upperStats.legalMovesSearched > 0 && upper <= narrowedBeta,
                "non-cutting TT upper bound must continue through ordinary search under the narrowed beta");
    }

    private static void testStalemateAndMateRemainTerminal() throws Exception {
        Board stale = board("7k/8/5KQ1/8/8/8/8/8 b - - 0 1");
        check(!MoveGenerator.hasAnyLegalMove(stale, new MoveList()),
                "stalemate fixture must be terminal");
        Search staleSearch = search(true, true, 100);
        check(!staleSearch.hasAnyLegalMoveForRfpTesting(stale, 1),
                "RFP's allocation-free legality guard must reject stalemate");
        check(search(true, true, 100).hasAnyLegalMoveForRfpTesting(
                        board(ADVANTAGE_FEN), 1),
                "RFP's legality guard must accept the tactical fixture's legal moves");
        int staleScore = invokeNegamax(staleSearch, stale, 1, -1, 0, 1, true);
        Search.SearchStats staleStats = captureStats(staleSearch);
        check(staleScore == 0, "RFP-enabled search must preserve stalemate score");
        check(staleStats.rfpCutoffs == 0, "stalemate cannot be reported as an RFP cutoff");
        check(staleStats.rfpStaticEvalCalls == 0,
                "zugzwang material gate should skip the stalemated king-only side");

        Board guardedStale = board("R5nk/5K1n/6Q1/8/8/8/8/7R b - - 0 1");
        check(!guardedStale.isInCheck(Piece.BLACK)
                        && !MoveGenerator.hasAnyLegalMove(guardedStale, new MoveList()),
                "qualifying-material fixture must be a legal stalemate, not checkmate");
        Search guardedStaleSearch = search(true, true, 125);
        BoardState guardedStaleBefore = BoardState.capture(guardedStale);
        int guardedStaticEval = Evaluator.evaluate(guardedStale);
        int guardedBeta = guardedStaticEval - 125;
        long[] guardedHistory = new long[Search.MAX_PLY + 8];
        guardedHistory[0] = 0x123456789abcdef0L;
        setSearchHistory(guardedStaleSearch, 0, guardedHistory);
        int guardedStaleScore = invokeNegamax(guardedStaleSearch, guardedStale, 1,
                guardedBeta - 1, guardedBeta, 1, true);
        Search.SearchStats guardedStaleStats = captureStats(guardedStaleSearch);
        check(guardedStaleScore == 0,
                "qualifying RFP stalemate must return draw rather than a fail-high");
        check(guardedStaleStats.rfpEligibleNodes == 1
                        && guardedStaleStats.rfpStaticEvalCalls == 1
                        && guardedStaleStats.rfpCutoffs == 0
                        && guardedStaleStats.rfpStalemateGuards == 1,
                "qualifying stalemate must reach and be stopped by the legal-move guard");
        guardedStaleBefore.assertUnchanged(guardedStale);
        check(boardHistorySize(guardedStale) == 0
                        && guardedHistory[1] == guardedStale.zobristKey,
                "qualifying stalemate guard must restore board and preserve search history");

        Board mate = board("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1");
        Search mateSearch = search(true, true, 100);
        int mateScore = invokeNegamax(mateSearch, mate, 1, -Evaluator.INFINITY_SCORE,
                Evaluator.INFINITY_SCORE, 1, true);
        check(mateScore == -(Evaluator.MATE_SCORE - 1),
                "checkmate must retain its ply-adjusted mate score");
        check(captureStats(mateSearch).rfpCutoffs == 0,
                "checkmate must never be returned by RFP");
    }

    private static void testHistoryAndFiftyMovePrecedence() throws Exception {
        Board repeated = board(ADVANTAGE_FEN);
        Search repeatedSearch = search(true, true, 100);
        setHistory(repeatedSearch, 2, repeated.zobristKey);
        int repetitionScore = invokeNegamax(repeatedSearch, repeated, 1, 0, 1, 1, true);
        Search.SearchStats repetitionStats = captureStats(repeatedSearch);
        check(repetitionScore == 0, "twofold search cycle must remain a draw");
        check(repetitionStats.repetitionExits == 1 && repetitionStats.rfpEligibilityChecks == 0,
                "repetition must exit before RFP eligibility work");

        Board fifty = board("4k3/ppp2ppp/2n5/8/3Q4/8/PPP2PPP/4K2R w - - 100 50");
        Search fiftySearch = search(true, true, 100);
        int fiftyScore = invokeNegamax(fiftySearch, fifty, 1, 0, 1, 1, true);
        Search.SearchStats fiftyStats = captureStats(fiftySearch);
        check(fiftyScore == 0 && fiftyStats.fiftyMoveExits == 1,
                "fifty-move draw must retain precedence over RFP");
        check(fiftyStats.rfpEligibilityChecks == 0,
                "fifty-move exit must happen before RFP eligibility work");

        Board historicalRoot = board(ADVANTAGE_FEN);
        Search historical = search(true, true, 100);
        long[] gameKeys = new long[Search.MAX_PLY + 8];
        gameKeys[0] = historicalRoot.zobristKey;
        setSearchHistory(historical, 1, gameKeys);
        invokeNegamax(historical, historicalRoot, 1, -1, 1, 0, true);
        Search.SearchStats historicalStats = captureStats(historical);
        check(historicalStats.rfpEligibilityChecks == 1
                        && historicalStats.rfpEligibleNodes == 0
                        && historicalStats.rfpStaticEvalCalls == 0,
                "full game history making root repetition-sensitive must prevent RFP evaluation");
    }

    private static void testCutoffRestoresBoardAndSearchHistory() throws Exception {
        Board position = board(ADVANTAGE_FEN);
        BoardState before = BoardState.capture(position);
        int staticEval = Evaluator.evaluate(position);
        int beta = staticEval - 125;
        Search search = search(true, true, 125);
        long[] searchKeys = new long[Search.MAX_PLY + 8];
        searchKeys[0] = 0x123456789abcdef0L;
        setSearchHistory(search, 0, searchKeys);
        long[] keysBefore = searchKeys.clone();
        keysBefore[1] = position.zobristKey; // ordinary negamax writes this node's key

        int score = invokeNegamax(search, position, 1, beta - 1, beta, 1, true);
        check(score == beta && captureStats(search).rfpCutoffs == 1,
                "restoration fixture must take the RFP cutoff path");
        before.assertUnchanged(position);
        check(boardHistorySize(position) == 0,
                "legal-move probe must restore the board's internal make/unmake stack");
        check(java.util.Arrays.equals(keysBefore, searchKeys),
                "RFP cutoff and legality probe must not overwrite caller search-history entries");
        check(getHistoryBase(search) == 0,
                "RFP cutoff must retain the supplied history base");
        check(searchKeys[1] == position.zobristKey,
                "negamax must retain its normal current-position history key");
    }

    private static void testInstrumentationOffAndCountersReset() throws Exception {
        Board enabledBoard = board(ADVANTAGE_FEN);
        Search enabled = search(true, true, 100);
        int enabledScore = invokeNegamax(enabled, enabledBoard, 1, 500, 501, 1, true);
        Search.SearchStats enabledStats = captureStats(enabled);
        check(enabledStats.rfpCutoffs == 1 && enabledStats.rfpCutoffsDepth1 == 1,
                "enabled instrumentation must expose RFP activity");

        Board disabledBoard = board(ADVANTAGE_FEN);
        Search disabled = search(false, true, 100);
        int enabledOffScore = invokeNegamax(disabled, disabledBoard, 1, 500, 501, 1, true);
        Search.SearchStats disabledStats = captureStats(disabled);
        check(!disabledStats.instrumentationEnabled && disabledStats.rfpEligibilityChecks == 0
                        && disabledStats.rfpEligibleNodes == 0 && disabledStats.rfpStaticEvalCalls == 0
                        && disabledStats.rfpCutoffs == 0 && disabledStats.rfpStalemateGuards == 0
                        && disabledStats.rfpEligibleDepth1 == 0 && disabledStats.rfpEligibleDepth2 == 0
                        && disabledStats.rfpEligibleDepth3 == 0 && disabledStats.rfpCutoffsDepth1 == 0
                        && disabledStats.rfpCutoffsDepth2 == 0 && disabledStats.rfpCutoffsDepth3 == 0,
                "all detailed RFP counters must stay zero when instrumentation is disabled");
        check(enabledOffScore == enabledScore
                        && disabledStats.totalNodes == enabledStats.totalNodes,
                "instrumentation on/off must preserve active RFP score and node count");

        Search disabledPolicy = search(true, false, 100);
        Board baselineModeBoard = board(ADVANTAGE_FEN);
        invokeNegamax(disabledPolicy, baselineModeBoard,
                1, 500, 501, 1, true);
        Search.SearchStats baselineModeStats = captureStats(disabledPolicy);
        check(baselineModeStats.legalMovesSearched > 0 && baselineModeStats.rfpCutoffs == 0
                        && baselineModeStats.rfpStaticEvalCalls == 0,
                "RFP-disabled policy must continue through baseline move search");

        Search reset = search(true, true, 100);
        invokeNegamax(reset, board(ADVANTAGE_FEN), 1, 500, 501, 1, true);
        check(captureStats(reset).rfpCutoffs == 1,
                "precondition: first invocation should record an RFP cutoff");
        reset.search(board(ADVANTAGE_FEN), 1, -1, null);
        Search.SearchStats afterSearch = reset.getLastStats();
        check(afterSearch.rfpCutoffs == 0 && afterSearch.rfpEligibleNodes == 0
                        && afterSearch.rfpStaticEvalCalls == 0,
                "public search must reset all RFP counters for its new search call");
    }

    private static void testPublicSearchReturnsLegalMove() {
        Board position = board(ADVANTAGE_FEN);
        Search search = search(true, true, 125);
        int bestMove = search.search(position, 2, -1, null);
        check(bestMove != Move.NONE,
                "public UCI-facing search must return a move when legal moves exist");
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(position, legal);
        boolean found = false;
        for (int i = 0; i < legal.size; i++) {
            if (legal.get(i) == bestMove) found = true;
        }
        check(found, "public search best move must be legal in the root position");
    }

    private static boolean eligible(Search search, Board board, int depth, int ply,
                                    boolean pv, boolean inCheck, boolean nullOk,
                                    boolean repeated, int alpha, int beta) {
        return search.isReverseFutilityEligibleForTesting(board, depth, ply, pv,
                inCheck, nullOk, repeated, alpha, beta);
    }

    private static Search search(boolean instrumentation, boolean rfp, int margin) {
        Search search = new Search(new TranspositionTable(1));
        search.setInstrumentationEnabled(instrumentation);
        search.setReverseFutilityEnabledForTesting(rfp);
        search.setReverseFutilityMarginForTesting(margin);
        return search;
    }

    private static int invokeNegamax(Search search, Board board, int depth,
                                     int alpha, int beta, int ply, boolean nullOk)
            throws Exception {
        Field historyBase = Search.class.getDeclaredField("historyBase");
        Field keyStack = Search.class.getDeclaredField("keyStack");
        historyBase.setAccessible(true);
        keyStack.setAccessible(true);
        if (keyStack.get(search) == null) {
            historyBase.setInt(search, 0);
            keyStack.set(search, new long[Search.MAX_PLY + 8]);
        }
        Method negamax = Search.class.getDeclaredMethod("negamax", Board.class, int.class,
                int.class, int.class, int.class, boolean.class);
        negamax.setAccessible(true);
        return (Integer) negamax.invoke(search, board, depth, alpha, beta, ply, nullOk);
    }

    private static void setHistory(Search search, int base, long repeatedKey) throws Exception {
        Field historyBase = Search.class.getDeclaredField("historyBase");
        Field keyStack = Search.class.getDeclaredField("keyStack");
        historyBase.setAccessible(true);
        keyStack.setAccessible(true);
        historyBase.setInt(search, base);
        long[] keys = new long[base + Search.MAX_PLY + 8];
        keys[1] = repeatedKey;
        keyStack.set(search, keys);
    }

    private static void setSearchHistory(Search search, int base, long[] keys) throws Exception {
        Field historyBase = Search.class.getDeclaredField("historyBase");
        Field keyStack = Search.class.getDeclaredField("keyStack");
        historyBase.setAccessible(true);
        keyStack.setAccessible(true);
        historyBase.setInt(search, base);
        keyStack.set(search, keys);
    }

    private static int getHistoryBase(Search search) throws Exception {
        Field historyBase = Search.class.getDeclaredField("historyBase");
        historyBase.setAccessible(true);
        return historyBase.getInt(search);
    }

    private static int boardHistorySize(Board board) throws Exception {
        Field historySize = Board.class.getDeclaredField("historySize");
        historySize.setAccessible(true);
        return historySize.getInt(board);
    }

    private static final class BoardState {
        private final String fen;
        private final long zobristKey;
        private final long pawnZobristKey;
        private final long whiteOccupancy;
        private final long blackOccupancy;
        private final long allOccupancy;
        private final int material;
        private final int mgPst;
        private final int egPst;
        private final int phase;

        private BoardState(Board board) {
            fen = board.toFen();
            zobristKey = board.zobristKey;
            pawnZobristKey = board.pawnZobristKey;
            whiteOccupancy = board.occupancy[Piece.WHITE];
            blackOccupancy = board.occupancy[Piece.BLACK];
            allOccupancy = board.allOccupancy;
            material = board.incrementalMaterialScore;
            mgPst = board.incrementalMgPstScore;
            egPst = board.incrementalEgPstScore;
            phase = board.phaseUnits;
        }

        static BoardState capture(Board board) { return new BoardState(board); }

        void assertUnchanged(Board board) {
            check(fen.equals(board.toFen()) && zobristKey == board.zobristKey
                            && pawnZobristKey == board.pawnZobristKey
                            && whiteOccupancy == board.occupancy[Piece.WHITE]
                            && blackOccupancy == board.occupancy[Piece.BLACK]
                            && allOccupancy == board.allOccupancy
                            && material == board.incrementalMaterialScore
                            && mgPst == board.incrementalMgPstScore
                            && egPst == board.incrementalEgPstScore
                            && phase == board.phaseUnits,
                    "RFP cutoff must restore board keys, occupancy, and incremental evaluation state");
        }
    }

    private static Search.SearchStats captureStats(Search search) throws Exception {
        Method capture = Search.SearchStats.class.getDeclaredMethod("capture", Search.class);
        capture.setAccessible(true);
        return (Search.SearchStats) capture.invoke(null, search);
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

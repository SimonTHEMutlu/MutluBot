package engine;

import java.util.Arrays;

import static engine.Piece.*;
import static engine.Evaluator.*;

/**
 * Negamax alpha-beta search with:
 *   - iterative deepening
 *   - a transposition table
 *   - MVV-LVA capture ordering, killer moves, history heuristic
 *   - null-move pruning
 *   - a simple form of PVS (principal variation search) with late move reductions
 *   - quiescence search with SEE and delta pruning at leaf nodes
 *
 * This is intentionally a "solid but simple" search. Natural next steps to
 * strengthen it: aspiration windows, better LMR conditions, futility/razoring
 * pruning, static exchange evaluation (SEE) for capture ordering, a real
 * multi-ply PV table instead of reconstructing the PV from the TT, etc.
 */
public class Search {
    interface RootCandidateListener {
        void onCandidate(int depth, int rank, String move, int score,
                         boolean exact, long nodes, String pv);
    }

    interface ProphylaxisCandidateListener {
        void onCandidate(int depth, String move, int normalScore, int threatScore,
                         int adjustmentCp, long threatMetrics, String threatLine,
                         int probeEvaluations);
    }

    interface ProphylaxisPlanListener {
        void onPlan(String line, int rootScore);
    }

    static final class ProphylaxisDiagnostics {
        final int depth;
        final int baselineThreatScore;
        final long baselineThreatMetrics;
        final int candidatesProbed;
        final int leafEvaluations;
        final int orderingEvaluations;
        final int verificationNodes;
        final int checkedThreats;
        final String selectedMove;
        final int selectedNormalScore;
        final int selectedThreatScore;
        final int selectedAdjustmentCp;
        final long selectedThreatMetrics;
        final String selectedThreatLine;

        private ProphylaxisDiagnostics(int depth, int baselineThreatScore,
                                       long baselineThreatMetrics, int candidatesProbed,
                                       int leafEvaluations, int orderingEvaluations,
                                       int verificationNodes, int checkedThreats,
                                       String selectedMove, int selectedNormalScore,
                                       int selectedThreatScore, int selectedAdjustmentCp,
                                       long selectedThreatMetrics, String selectedThreatLine) {
            this.depth = depth;
            this.baselineThreatScore = baselineThreatScore;
            this.baselineThreatMetrics = baselineThreatMetrics;
            this.candidatesProbed = candidatesProbed;
            this.leafEvaluations = leafEvaluations;
            this.orderingEvaluations = orderingEvaluations;
            this.verificationNodes = verificationNodes;
            this.checkedThreats = checkedThreats;
            this.selectedMove = selectedMove;
            this.selectedNormalScore = selectedNormalScore;
            this.selectedThreatScore = selectedThreatScore;
            this.selectedAdjustmentCp = selectedAdjustmentCp;
            this.selectedThreatMetrics = selectedThreatMetrics;
            this.selectedThreatLine = selectedThreatLine;
        }

        String toSummary() {
            return "depth=" + depth
                    + " baseline=" + baselineThreatScore
                    + " baselineMetrics=" + formatThreatMetrics(baselineThreatMetrics)
                    + " candidates=" + candidatesProbed
                    + " leaves=" + leafEvaluations
                    + " orderEvals=" + orderingEvaluations
                    + " verifyNodes=" + verificationNodes
                    + " checkedThreats=" + checkedThreats
                    + " selected=" + selectedMove
                    + " normal=" + selectedNormalScore
                    + " threat=" + selectedThreatScore
                    + " adjustment=" + selectedAdjustmentCp
                    + " selectedMetrics=" + formatThreatMetrics(selectedThreatMetrics)
                    + " line=" + selectedThreatLine;
        }
    }

    private static final int PROPHYLAXIS_MIN_DEPTH = 3;
    private static final int PROPHYLAXIS_MAX_CANDIDATES = 8;
    private static final int PROPHYLAXIS_MAX_DIRECT_CANDIDATES = 2;
    private static final int PROPHYLAXIS_MAX_PENDING_PROBES = 3;
    private static final int PROPHYLAXIS_MAX_PENDING_ALTERNATIVES = 8;
    private static final int PROPHYLAXIS_MAX_ROOT_RANK = 8;
    private static final int PROPHYLAXIS_NORMAL_SCORE_BAND = 25;
    private static final int PROPHYLAXIS_MAX_ADJUSTMENT = 12;
    private static final int PROPHYLAXIS_MAX_EVALUATIONS = 512;
    private static final int PROPHYLAXIS_BASELINE_EVALUATIONS = 64;
    private static final int PROPHYLAXIS_CANDIDATE_EVALUATIONS = 64;
    private static final int PROPHYLAXIS_FIRST_MOVE_LIMIT = 16;
    private static final int PROPHYLAXIS_EVASION_LIMIT = 16;
    private static final int PROPHYLAXIS_CONTINUATION_LIMIT = 3;
    private static final int PROPHYLAXIS_MOVE_BUFFER = 256;


    public static final int MAX_PLY = 128;

    private final TranspositionTable tt;
    private final int[][] killerMoves = new int[MAX_PLY][2];
    private final int[][] historyTable = new int[64][64];
    private final int[][] seeGains = new int[MAX_PLY][32];
    // Each recursion level owns reusable buffers so nodes do not create garbage.
    private final MoveList[] moveLists = new MoveList[MAX_PLY];
    private final int[][] moveScores = new int[MAX_PLY][256];
    private final boolean[] repetitionTainted = new boolean[MAX_PLY];
    private long[] keyStack;
    private int historyBase;

    private volatile boolean stopRequested;
    private long nodes;
    private long mainSearchNodes;
    private long quiescenceNodes;
    private long evaluatorCalls;
    private long maxSelectiveDepth;
    private long ttProbes;
    private long ttHits;
    private long ttExactHits;
    private long ttMoveAvailable;
    private long ttBoundCutoffs;
    private long mainSearchBetaCutoffs;
    private long quiescenceBetaCutoffs;
    private long firstMoveCutoffs;
    private long legalMovesSearched;
    private long lmrAttempts;
    private long lmrReducedSearches;
    private long lmrFullDepthResearches;
    private long lmrReductionPlies;
    private long lmrDeepReductions;
    private long seeCalls;
    private long mainTieBreakSeeCalls;
    private long qPruneSeeCalls;
    private long mainSeeDemotions;
    private long nullMoveAttempts;
    private long nullMoveCutoffs;
    private long aspirationRetries;
    private long repetitionExits;
    private long fiftyMoveExits;
    private long deadlineNanos;
    private long softTimeNanos;
    private boolean timeLimited;

    private int rootBestMove;
    private int iterationBestMove;
    private int iterationReportedScore = Integer.MIN_VALUE;
    private int selDepth;
    private SearchStats lastStats = SearchStats.empty();

    /** Immutable counters captured after the most recent completed search call. */
    public static final class SearchStats {
        public final long totalNodes;
        public final long mainSearchNodes;
        public final long quiescenceNodes;
        public final long evaluatorCalls;
        public final long maxSelectiveDepth;
        public final long ttProbes;
        public final long ttHits;
        public final long ttExactHits;
        public final long ttMoveAvailable;
        public final long ttBoundCutoffs;
        public final long mainSearchBetaCutoffs;
        public final long quiescenceBetaCutoffs;
        public final long betaCutoffs;
        public final long firstMoveCutoffs;
        public final long legalMovesSearched;
        public final long lmrAttempts;
        public final long lmrReducedSearches;
        public final long lmrFullDepthResearches;
        public final long lmrReductionPlies;
        public final long lmrDeepReductions;
        public final long seeCalls;
        public final long mainTieBreakSeeCalls;
        /** @deprecated use mainTieBreakSeeCalls. */
        public final long mainOrderSeeCalls;
        public final long qPruneSeeCalls;
        public final long mainSeeDemotions;
        public final long nullMoveAttempts;
        public final long nullMoveCutoffs;
        public final long aspirationRetries;
        public final long repetitionExits;
        public final long fiftyMoveExits;
        public final boolean instrumentationEnabled;

        private SearchStats(long totalNodes, long mainSearchNodes, long quiescenceNodes,
                            long evaluatorCalls, long maxSelectiveDepth, long ttProbes,
                            long ttHits, long ttExactHits, long ttMoveAvailable,
                            long ttBoundCutoffs, long mainSearchBetaCutoffs,
                            long quiescenceBetaCutoffs, long firstMoveCutoffs,
                            long legalMovesSearched, long lmrAttempts, long lmrReducedSearches,
                            long lmrFullDepthResearches, long lmrReductionPlies,
                            long lmrDeepReductions, long seeCalls, long mainTieBreakSeeCalls,
                            long qPruneSeeCalls, long mainSeeDemotions, long nullMoveAttempts,
                            long nullMoveCutoffs, long aspirationRetries, long repetitionExits,
                            long fiftyMoveExits, boolean instrumentationEnabled) {
            this.totalNodes = totalNodes;
            this.mainSearchNodes = mainSearchNodes;
            this.quiescenceNodes = quiescenceNodes;
            this.evaluatorCalls = evaluatorCalls;
            this.maxSelectiveDepth = maxSelectiveDepth;
            this.ttProbes = ttProbes;
            this.ttHits = ttHits;
            this.ttExactHits = ttExactHits;
            this.ttMoveAvailable = ttMoveAvailable;
            this.ttBoundCutoffs = ttBoundCutoffs;
            this.mainSearchBetaCutoffs = mainSearchBetaCutoffs;
            this.quiescenceBetaCutoffs = quiescenceBetaCutoffs;
            this.betaCutoffs = mainSearchBetaCutoffs + quiescenceBetaCutoffs;
            this.firstMoveCutoffs = firstMoveCutoffs;
            this.legalMovesSearched = legalMovesSearched;
            this.lmrAttempts = lmrAttempts;
            this.lmrReducedSearches = lmrReducedSearches;
            this.lmrFullDepthResearches = lmrFullDepthResearches;
            this.lmrReductionPlies = lmrReductionPlies;
            this.lmrDeepReductions = lmrDeepReductions;
            this.seeCalls = seeCalls;
            this.mainTieBreakSeeCalls = mainTieBreakSeeCalls;
            this.mainOrderSeeCalls = mainTieBreakSeeCalls;
            this.qPruneSeeCalls = qPruneSeeCalls;
            this.mainSeeDemotions = mainSeeDemotions;
            this.nullMoveAttempts = nullMoveAttempts;
            this.nullMoveCutoffs = nullMoveCutoffs;
            this.aspirationRetries = aspirationRetries;
            this.repetitionExits = repetitionExits;
            this.fiftyMoveExits = fiftyMoveExits;
            this.instrumentationEnabled = instrumentationEnabled;
        }

        private static SearchStats empty() {
            return new SearchStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
        }

        private static SearchStats capture(Search search) {
            boolean enabled = search.instrumentationEnabled;
            return new SearchStats(search.nodes,
                    enabled ? search.mainSearchNodes : 0,
                    enabled ? search.quiescenceNodes : 0,
                    enabled ? search.evaluatorCalls : 0,
                    search.maxSelectiveDepth,
                    enabled ? search.ttProbes : 0,
                    enabled ? search.ttHits : 0,
                    enabled ? search.ttExactHits : 0,
                    enabled ? search.ttMoveAvailable : 0,
                    enabled ? search.ttBoundCutoffs : 0,
                    enabled ? search.mainSearchBetaCutoffs : 0,
                    enabled ? search.quiescenceBetaCutoffs : 0,
                    enabled ? search.firstMoveCutoffs : 0,
                    enabled ? search.legalMovesSearched : 0,
                    enabled ? search.lmrAttempts : 0,
                    enabled ? search.lmrReducedSearches : 0,
                    enabled ? search.lmrFullDepthResearches : 0,
                    enabled ? search.lmrReductionPlies : 0,
                    enabled ? search.lmrDeepReductions : 0,
                    enabled ? search.seeCalls : 0,
                    enabled ? search.mainTieBreakSeeCalls : 0,
                    enabled ? search.qPruneSeeCalls : 0,
                    enabled ? search.mainSeeDemotions : 0,
                    enabled ? search.nullMoveAttempts : 0,
                    enabled ? search.nullMoveCutoffs : 0,
                    enabled ? search.aspirationRetries : 0,
                    enabled ? search.repetitionExits : 0,
                    enabled ? search.fiftyMoveExits : 0,
                    enabled);
        }

        /** Compact key/value form suitable for benchmark logs and scripts. */
        public String toSummary() {
            return "mainNodes=" + mainSearchNodes
                    + " qNodes=" + quiescenceNodes
                    + " evalCalls=" + evaluatorCalls
                    + " seldepth=" + maxSelectiveDepth
                    + " ttProbes=" + ttProbes
                    + " ttHits=" + ttHits
                    + " ttExactHits=" + ttExactHits
                    + " ttMoves=" + ttMoveAvailable
                    + " ttBoundCutoffs=" + ttBoundCutoffs
                    + " mainBetaCutoffs=" + mainSearchBetaCutoffs
                    + " qBetaCutoffs=" + quiescenceBetaCutoffs
                    + " betaCutoffs=" + betaCutoffs
                    + " firstMoveCutoffs=" + firstMoveCutoffs
                    + " legalMoves=" + legalMovesSearched
                    + " lmrAttempts=" + lmrAttempts
                    + " lmrReduced=" + lmrReducedSearches
                    + " lmrResearch=" + lmrFullDepthResearches
                    + " lmrReductionPlies=" + lmrReductionPlies
                    + " lmrDeep=" + lmrDeepReductions
                    + " seeCalls=" + seeCalls
                    + " mainTieBreakSeeCalls=" + mainTieBreakSeeCalls
                    + " qSeePruneCalls=" + qPruneSeeCalls
                    + " mainSeeDemotions=" + mainSeeDemotions
                    + " nullAttempts=" + nullMoveAttempts
                    + " nullCutoffs=" + nullMoveCutoffs
                    + " aspirationRetries=" + aspirationRetries
                    + " repetitionExits=" + repetitionExits
                    + " fiftyMoveExits=" + fiftyMoveExits;
        }
    }

    public interface InfoListener {
        void onInfo(int depth, int seldepth, int scoreCp, boolean isMate, int mateIn,
                    long nodes, long nps, long timeMs, String pv);
    }

    private InfoListener listener;
    private boolean instrumentationEnabled;
    private boolean lmrEnabled = true;
    private RootCandidateListener rootCandidateListener;
    private boolean prophylaxisProbeEnabled;
    private ProphylaxisCandidateListener prophylaxisCandidateListener;
    private ProphylaxisPlanListener prophylaxisPlanListener;
    private ProphylaxisDiagnostics lastProphylaxisDiagnostics;
    private final int[] prophylaxisMoves = new int[PROPHYLAXIS_MAX_CANDIDATES];
    private final int[] prophylaxisNormalScores = new int[PROPHYLAXIS_MAX_CANDIDATES];
    private final boolean[] prophylaxisCandidateExact = new boolean[PROPHYLAXIS_MAX_CANDIDATES];
    private final int[] prophylaxisThreatScores = new int[PROPHYLAXIS_MAX_CANDIDATES];
    private final int[] prophylaxisAdjustments = new int[PROPHYLAXIS_MAX_CANDIDATES];
    private final long[] prophylaxisMetrics = new long[PROPHYLAXIS_MAX_CANDIDATES];
    private final String[] prophylaxisLines = new String[PROPHYLAXIS_MAX_CANDIDATES];
    private final int[] prophylaxisPendingMoves = new int[PROPHYLAXIS_MAX_PENDING_ALTERNATIVES];
    private final int[] prophylaxisPendingScores = new int[PROPHYLAXIS_MAX_PENDING_ALTERNATIVES];
    private final MoveList[] prophylaxisMoveLists = new MoveList[3];
    private final int[][] prophylaxisOrderedMoves = new int[3][PROPHYLAXIS_FIRST_MOVE_LIMIT];
    private final int[][] prophylaxisOrderedScores = new int[3][PROPHYLAXIS_FIRST_MOVE_LIMIT];
    private final int[][] prophylaxisOrderedLeafScores = new int[3][PROPHYLAXIS_FIRST_MOVE_LIMIT];
    private int prophylaxisCandidatesProbed;
    private int prophylaxisLeaves;
    private int prophylaxisOrderingEvaluations;
    private int prophylaxisPendingCount;
    private int prophylaxisCheckedThreats;
    private int prophylaxisVerificationNodes;
    private int prophylaxisEvaluations;
    private int prophylaxisDepth;
    private int prophylaxisBaselineScore;
    private long prophylaxisBaselineMetrics;
    private boolean prophylaxisBaselineReady;

    public Search(TranspositionTable tt) {
        this.tt = tt;
        for (int ply = 0; ply < MAX_PLY; ply++) {
            moveLists[ply] = new MoveList();
        }
        for (int i = 0; i < prophylaxisMoveLists.length; i++) {
            prophylaxisMoveLists[i] = new MoveList();
        }
    }

    public void setInfoListener(InfoListener l) {
        this.listener = l;
    }

    public void requestStop() {
        stopRequested = true;
    }

    /** Enables detailed counters. Disabled by default to keep normal play fast. */
    public void setInstrumentationEnabled(boolean enabled) {
        instrumentationEnabled = enabled;
    }

    /**
     * Returns an immutable snapshot from the most recent search call. When
     * instrumentation is disabled, totalNodes and maxSelectiveDepth remain
     * available, while detailed counters are zero and instrumentationEnabled
     * is false.
     */
    public SearchStats getLastStats() {
        return lastStats;
    }

    void setLmrEnabledForTesting(boolean enabled) {
        lmrEnabled = enabled;
    }

    void setRootCandidateListener(RootCandidateListener listener) {
        rootCandidateListener = listener;
    }

    /** Enables the bounded root threat experiment. Disabled by default. */
    void setProphylaxisProbeEnabled(boolean enabled) {
        prophylaxisProbeEnabled = enabled;
    }

    void setProphylaxisCandidateListener(ProphylaxisCandidateListener listener) {
        prophylaxisCandidateListener = listener;
    }

    void setProphylaxisPlanListener(ProphylaxisPlanListener listener) {
        prophylaxisPlanListener = listener;
    }

    ProphylaxisDiagnostics getLastProphylaxisDiagnostics() {
        return lastProphylaxisDiagnostics;
    }

    /**
     * Iterative deepening search from the current position on `board`.
     *
     * @param maxDepth       depth limit, or <= 0 for "no explicit depth limit"
     * @param timeMillis     time budget in ms, or < 0 for "no time limit" (rely on stop()/maxDepth)
     * @param gameHistoryKeys zobrist keys of positions played so far in the actual game
     *                        (oldest first), used for accurate repetition detection; may be null.
     *                        The current root may be present as the final entry.
     */
    public int search(Board board, int maxDepth, long timeMillis, long[] gameHistoryKeys) {
        return search(board, maxDepth, timeMillis, timeMillis, gameHistoryKeys);
    }

    /** Search with a soft completed-iteration target and an enforced hard deadline. */
    public int search(Board board, int maxDepth, long softTimeMillis, long hardTimeMillis,
                      long[] gameHistoryKeys) {
        stopRequested = false;
        lastProphylaxisDiagnostics = null;
        nodes = 0;
        mainSearchNodes = 0;
        quiescenceNodes = 0;
        evaluatorCalls = 0;
        maxSelectiveDepth = 0;
        selDepth = 0;
        ttProbes = 0;
        ttHits = 0;
        ttExactHits = 0;
        ttMoveAvailable = 0;
        ttBoundCutoffs = 0;
        mainSearchBetaCutoffs = 0;
        quiescenceBetaCutoffs = 0;
        firstMoveCutoffs = 0;
        legalMovesSearched = 0;
        lmrAttempts = 0;
        lmrReducedSearches = 0;
        lmrFullDepthResearches = 0;
        lmrReductionPlies = 0;
        lmrDeepReductions = 0;
        seeCalls = 0;
        mainTieBreakSeeCalls = 0;
        qPruneSeeCalls = 0;
        mainSeeDemotions = 0;
        nullMoveAttempts = 0;
        nullMoveCutoffs = 0;
        aspirationRetries = 0;
        repetitionExits = 0;
        fiftyMoveExits = 0;
        tt.newSearch();
        for (int[] k : killerMoves) Arrays.fill(k, Move.NONE);
        for (int[] row : historyTable) Arrays.fill(row, 0);

        int historyLength = gameHistoryKeys != null ? gameHistoryKeys.length : 0;
        boolean historyIncludesRoot = historyLength > 0
                && gameHistoryKeys[historyLength - 1] == board.zobristKey;
        historyBase = historyIncludesRoot ? historyLength - 1 : historyLength;
        keyStack = new long[historyBase + MAX_PLY + 8];
        if (gameHistoryKeys != null && historyBase > 0) {
            System.arraycopy(gameHistoryKeys, 0, keyStack, 0, historyBase);
        }

        long startNanos = System.nanoTime();
        long hardNanos = millisToNanos(hardTimeMillis);
        timeLimited = hardTimeMillis >= 0 && hardNanos != Long.MAX_VALUE;
        deadlineNanos = timeLimited ? deadlineAfter(startNanos, hardNanos) : Long.MAX_VALUE;
        softTimeNanos = softTimeMillis >= 0 ? millisToNanos(softTimeMillis) : Long.MAX_VALUE;

        int depthLimit = maxDepth > 0 ? maxDepth : MAX_PLY - 4;
        rootBestMove = Move.NONE;
        iterationBestMove = Move.NONE;
        MoveList rootMoves = new MoveList();
        MoveGenerator.generateLegal(board, rootMoves);
        if (rootMoves.size <= 1) {
            rootBestMove = rootMoves.size == 1 ? rootMoves.get(0) : Move.NONE;
            if (rootMoves.size == 0 && listener != null) {
                int terminalScore = board.isInCheck(board.sideToMove) ? -MATE_SCORE : 0;
                reportInfo(board, 1, terminalScore, (System.nanoTime() - startNanos) / 1_000_000L);
            }
            lastStats = SearchStats.capture(this);
            return rootBestMove;
        }
        int bestScore = 0;
        int window = 25; // centipawns, tunable
        int alpha, beta;
        long lastIterationNanos = 0;
        long previousIterationNanos = 0;
        int stableIterations = 0;
        int previousBestMove = Move.NONE;
        int previousScore = 0;

        for (int depth = 1; depth <= depthLimit; depth++) {
            long now = System.nanoTime();
            long usedBeforeIteration = now - startNanos;
            // Normal clock mode predicts only against the hard deadline. Soft
            // stopping happens after an actual completed iteration; movetime
            // uses equal soft/hard limits and remains deadline-driven.
            if (timeLimited && softTimeNanos < hardNanos && lastIterationNanos > 0) {
                double growthFactor = previousIterationNanos > 0
                        ? Math.max(1.0, Math.min(3.0,
                                (double) lastIterationNanos / previousIterationNanos)) : 2.0;
                long growthEstimate = lastIterationNanos > Long.MAX_VALUE / growthFactor
                        ? Long.MAX_VALUE : (long) (lastIterationNanos * growthFactor);
                if (!SearchTimePolicy.shouldStartNextIteration(usedBeforeIteration,
                        growthEstimate, hardNanos)) break;
            }
            long iterationStartNanos = now;
            iterationBestMove = Move.NONE;
            iterationReportedScore = Integer.MIN_VALUE;
            if(depth <= 2 || Math.abs(bestScore) >= MATE_SCORE - MAX_PLY - 10)
            {
                alpha = -INFINITY_SCORE;
                beta = INFINITY_SCORE;
            }
            else{
                alpha = bestScore - window;
                beta = bestScore + window;
            }

            selDepth = 0;
            int score;
            while(true) {
                score = negamax(board, depth, alpha, beta, 0, true);
                if (stopRequested) break;
                if(score <= alpha){
                    if (instrumentationEnabled) aspirationRetries++;
                    alpha = -INFINITY_SCORE; // fail low - widen and retry at the same depth
                }  else if (score >= beta){
                    if (instrumentationEnabled) aspirationRetries++;
                    beta = INFINITY_SCORE; // fail high - widen and retry at the same depth
                }
                else{
                    break; // score landed inside the window - trustworthy, move on
                }
            }

            if (stopRequested && depth > 1) {
                break; // incomplete iteration; keep the previous depth's result
            }

            bestScore = score;
            if (iterationBestMove != Move.NONE) rootBestMove = iterationBestMove;
            if (rootBestMove == previousBestMove && Math.abs(bestScore - previousScore) <= 35) {
                stableIterations++;
            } else {
                stableIterations = 0;
            }
            previousBestMove = rootBestMove;
            previousScore = bestScore;
            previousIterationNanos = lastIterationNanos;
            lastIterationNanos = System.nanoTime() - iterationStartNanos;

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            if (listener != null) reportInfo(board, depth,
                    iterationReportedScore == Integer.MIN_VALUE ? bestScore : iterationReportedScore,
                    elapsedMs);

            if (stopRequested) break;
            long completedElapsedNanos = System.nanoTime() - startNanos;
            if (timeLimited && SearchTimePolicy.shouldStopAtSoftAfterCompletedIteration(
                    completedElapsedNanos, softTimeNanos, hardNanos, stableIterations >= 2)) {
                break;
            }
            if (Math.abs(bestScore) >= MATE_SCORE - MAX_PLY) break; // forced mate found
        }

        if (rootBestMove == Move.NONE) {
            MoveList legal = new MoveList();
            MoveGenerator.generateLegal(board, legal);
            if (legal.size > 0) rootBestMove = legal.get(0);
        }
        lastStats = SearchStats.capture(this);
        return rootBestMove;
    }

    private void reportInfo(Board board, int depth, int score, long elapsedMs) {
        long nps = elapsedMs > 0 ? nodes * 1000L / elapsedMs : nodes;
        boolean isMate = Math.abs(score) >= MATE_SCORE - MAX_PLY;
        int mateIn = 0;
        if (isMate) {
            int pliesToMate = MATE_SCORE - Math.abs(score);
            mateIn = (pliesToMate + 1) / 2;
            if (score < 0) mateIn = -mateIn;
        }
        String pv = extractPv(board, Math.max(depth, 1), iterationBestMove);
        listener.onInfo(depth, selDepth, score, isMate, mateIn, nodes, nps, elapsedMs, pv);
    }

    private String extractPv(Board board, int maxLen, int reportedRootMove) {
        StringBuilder sb = new StringBuilder();
        int madeMoves = 0;
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < maxLen; i++) {
            if (!seen.add(board.zobristKey)) break;
            int move;
            if (i == 0 && reportedRootMove != Move.NONE) {
                move = reportedRootMove;
            } else {
                TranspositionTable.Probe p = tt.probe(board.zobristKey);
                if (p == null || p.move == Move.NONE) break;
                move = p.move;
            }
            if (!isLegalInPosition(board, move)) break;
            board.makeMove(move);
            madeMoves++;
            sb.append(sb.length() == 0 ? "" : " ").append(Move.toUci(move));
        }
        for (int i = 0; i < madeMoves; i++) board.unmakeMove();
        return sb.toString();
    }

    private boolean isLegalInPosition(Board board, int move) {
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        for (int i = 0; i < legal.size; i++) if (legal.get(i) == move) return true;
        return false;
    }

    private boolean checkTime() {
        return timeLimited && System.nanoTime() - deadlineNanos >= 0;
    }

    private static long millisToNanos(long millis) {
        if (millis < 0 || millis >= Long.MAX_VALUE / 1_000_000L) return Long.MAX_VALUE;
        return millis * 1_000_000L;
    }

    private static long deadlineAfter(long startNanos, long durationNanos) {
        if (durationNanos == Long.MAX_VALUE || durationNanos > Long.MAX_VALUE / 2) {
            return Long.MAX_VALUE;
        }
        return startNanos + durationNanos;
    }

    private boolean hasNonPawnMaterial(Board b, int color) {
        return (b.pieceBB[color][KNIGHT] | b.pieceBB[color][BISHOP]
              | b.pieceBB[color][ROOK] | b.pieceBB[color][QUEEN]) != 0;
    }

    private int adjustMateToTT(int score, int searchPly) {
        if (score >= MATE_SCORE - MAX_PLY) return score + searchPly;
        if (score <= -(MATE_SCORE - MAX_PLY)) return score - searchPly;
        return score;
    }

    private int adjustMateFromTT(int score, int searchPly) {
        if (score >= MATE_SCORE - MAX_PLY) return score - searchPly;
        if (score <= -(MATE_SCORE - MAX_PLY)) return score + searchPly;
        return score;
    }

    /**
     * The root only scores a draw for a claimable third occurrence. Below the
     * root, a second occurrence is treated as a search cycle so the engine
     * does not prefer a reversible loop over a winning alternative.
     */
    private boolean isRepetitionDraw(int repetitions, int searchPly) {
        return repetitions >= (searchPly == 0 ? 2 : 1);
    }

    private int negamax(Board board, int depth, int alpha, int beta, int searchPly, boolean nullOk) {
        if ((nodes & 2047) == 0 && checkTime()) stopRequested = true;
        if (stopRequested) return 0;
        nodes++;
        if (instrumentationEnabled) mainSearchNodes++;
        observeSelectiveDepth(searchPly);

        repetitionTainted[searchPly] = false;

        if (searchPly >= MAX_PLY - 1) {
            if (instrumentationEnabled) evaluatorCalls++;
            return Evaluator.evaluate(board);
        }

        int absPly = historyBase + searchPly;
        keyStack[absPly] = board.zobristKey;
        if (board.halfmoveClock >= 100) {
            boolean checked = board.isInCheck(board.sideToMove);
            if (!checked || MoveGenerator.hasLegalMove(board)) {
                if (instrumentationEnabled) fiftyMoveExits++;
                return 0;
            }
            return -(MATE_SCORE - searchPly);
        }
        int repetitions = 0;
        for (int p = absPly - 2; p >= 0; p -= 2) {
            if (keyStack[p] == board.zobristKey) {
                repetitions++;
                repetitionTainted[searchPly] = true;
                if (isRepetitionDraw(repetitions, searchPly)) {
                    if (instrumentationEnabled) repetitionExits++;
                    return 0;
                }
            }
        }
        boolean repetitionSensitive = repetitions > 0;

        boolean inCheck = board.isInCheck(board.sideToMove);
        if (depth <= 0) {
            if (inCheck) depth = 1; // don't enter quiescence while in check
            else return quiescence(board, alpha, beta, searchPly);
        }

        int origAlpha = alpha;
        int ttMove = Move.NONE;
        if (instrumentationEnabled) ttProbes++;
        long entry = tt.probePacked(board.zobristKey);
        if (entry != 0) {
            if (instrumentationEnabled) ttHits++;
            ttMove = TranspositionTable.moveOf(entry);
            if (instrumentationEnabled && ttMove != Move.NONE) ttMoveAvailable++;
            // A TT score is safe only inside the search that produced it, and
            // not when this position has already occurred on the actual-game
            // history or active line. The move remains useful for ordering.
            if (!repetitionSensitive && tt.isCurrentGeneration(entry)
                    && TranspositionTable.depthOf(entry) >= depth) {
                int score = adjustMateFromTT(TranspositionTable.scoreOf(entry), searchPly);
                int flag = TranspositionTable.flagOf(entry);
                if (flag == TranspositionTable.EXACT) {
                    if (instrumentationEnabled) ttExactHits++;
                    if (searchPly == 0) iterationBestMove = ttMove;
                    return score;
                }
                if (flag == TranspositionTable.LOWER_BOUND && score > alpha) alpha = score;
                else if (flag == TranspositionTable.UPPER_BOUND && score < beta) beta = score;
                if (alpha >= beta) {
                    if (instrumentationEnabled) ttBoundCutoffs++;
                    if (searchPly == 0) iterationBestMove = ttMove;
                    return score;
                }
            }
        }
        if (nullOk && !inCheck && depth >= 3 && searchPly > 0
                && hasNonPawnMaterial(board, board.sideToMove)) {
            if (instrumentationEnabled) nullMoveAttempts++;
            board.makeNullMove();
            int score = -negamax(board, depth - 3, -beta, -beta + 1, searchPly + 1, false);
            board.unmakeNullMove();
            if (repetitionTainted[searchPly + 1]) repetitionTainted[searchPly] = true;
            if (stopRequested) return 0;
            if (score >= beta) {
                if (instrumentationEnabled) nullMoveCutoffs++;
                return beta;
            }
        }

        MoveList moves = moveLists[searchPly];
        moves.clear();
        MoveGenerator.generatePseudoLegal(board, moves, false);
        int[] scores = scoreBuffer(searchPly, moves.size);
        scoreMoves(board, moves, ttMove, searchPly, scores);

        int legalCount = 0;
        int bestScore = -INFINITY_SCORE;
        int bestMove = Move.NONE;
        int us = board.sideToMove;
        boolean probeThisRoot = searchPly == 0 && prophylaxisProbeEnabled
                && depth >= PROPHYLAXIS_MIN_DEPTH
                && isProphylaxisRootEligible(board, inCheck);
        if (probeThisRoot) beginProphylaxisRoot(board, depth, us);

        for (int i = 0; i < moves.size; i++) {
            int bestIdx = i;
            for (int j = i + 1; j < moves.size; j++) if (scores[j] > scores[bestIdx]) bestIdx = j;
            bestIdx = maybeDemoteLosingCapture(board, moves, scores, bestIdx, depth, searchPly);
            if (bestIdx != i) {
                int tmpM = moves.moves[i]; moves.moves[i] = moves.moves[bestIdx]; moves.moves[bestIdx] = tmpM;
                int tmpS = scores[i]; scores[i] = scores[bestIdx]; scores[bestIdx] = tmpS;
            }
            int move = moves.get(i);
            long candidateStartNodes = searchPly == 0 && rootCandidateListener != null
                    ? nodes : 0;
            int candidateAlpha = alpha;
            board.makeMove(move);
            if (board.isInCheck(us)) {
                board.unmakeMove();
                continue;
            }
            legalCount++;
            if (instrumentationEnabled) legalMovesSearched++;

            int score;
            boolean childRepetitionTainted;
            if (legalCount == 1) {
                score = -negamax(board, depth - 1, -beta, -alpha, searchPly + 1, true);
                childRepetitionTainted = repetitionTainted[searchPly + 1];
            } else {
                boolean givesCheck = board.isInCheck(board.sideToMove);
                boolean advancedPasser = isAdvancedPassedPawnPush(board, move, us);
                int reduction = lmrReductionForMove(move, depth, legalCount, searchPly,
                        ttMove, inCheck, givesCheck, advancedPasser, board, us);
                if (reduction > 0) {
                    if (instrumentationEnabled) {
                        lmrAttempts++;
                        lmrReducedSearches++;
                        lmrReductionPlies += reduction;
                        if (reduction >= 2) lmrDeepReductions++;
                    }
                }
                score = -negamax(board, depth - 1 - reduction, -alpha - 1, -alpha, searchPly + 1, true);
                childRepetitionTainted = repetitionTainted[searchPly + 1];
                if (score > alpha) {
                    if (instrumentationEnabled && reduction > 0) lmrFullDepthResearches++;
                    score = -negamax(board, depth - 1, -beta, -alpha, searchPly + 1, true);
                    childRepetitionTainted |= repetitionTainted[searchPly + 1];
                }
            }

            boolean candidateExact = score > candidateAlpha && score < beta;
            if (probeThisRoot && candidateExact && legalCount <= PROPHYLAXIS_MAX_ROOT_RANK
                    && !Move.isPromotion(move)
                    && !board.isInCheck(board.sideToMove)
                    && prophylaxisCandidatesProbed < PROPHYLAXIS_MAX_DIRECT_CANDIDATES
                    && prophylaxisEvaluations < PROPHYLAXIS_MAX_EVALUATIONS) {
                probeProphylaxisCandidate(board, move, score, depth, us);
            } else if (probeThisRoot && !candidateExact && score <= candidateAlpha
                    && legalCount <= PROPHYLAXIS_MAX_ROOT_RANK
                    && !Move.isPromotion(move) && !board.isInCheck(board.sideToMove)) {
                rememberProphylaxisAlternative(move, score);
            }
            board.unmakeMove();
            if (searchPly == 0 && rootCandidateListener != null) {
                String pv = extractPv(board, depth, move);
                rootCandidateListener.onCandidate(depth, legalCount, Move.toUci(move), score,
                        candidateExact,
                        nodes - candidateStartNodes, pv);
            }
            if (childRepetitionTainted) repetitionTainted[searchPly] = true;
            if (stopRequested) return 0;

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
            if (score > alpha) {
                alpha = score;
            }
            if (alpha >= beta) {
                if (instrumentationEnabled) {
                    mainSearchBetaCutoffs++;
                    if (legalCount == 1) firstMoveCutoffs++;
                }
                if (!Move.isCapture(move)) {
                    historyTable[Move.from(move)][Move.to(move)] += depth * depth;
                    if (killerMoves[searchPly][0] != move) {
                        killerMoves[searchPly][1] = killerMoves[searchPly][0];
                        killerMoves[searchPly][0] = move;
                    }
                }
                break;
            }
        }

        if (legalCount == 0) {
            return inCheck ? -(MATE_SCORE - searchPly) : 0;
        }

        boolean rootExact = bestScore > origAlpha && bestScore < beta;
        if (probeThisRoot && rootExact && !stopRequested) {
            ensureBestProphylaxisCandidate(board, bestMove, bestScore, depth, us);
            probePromisingPendingAlternatives(board, bestMove, bestScore, depth, us);
            RootVerification verification = verifyBestProphylaxisAlternative(
                    board, depth, bestMove, bestScore, us);
            if (verification != null && verification.completed) {
                if (verification.score > bestScore) {
                    bestScore = verification.score;
                    bestMove = verification.move;
                }
                if (!Move.isPromotion(verification.move)) {
                    int slot = findProphylaxisCandidate(verification.move);
                    if (slot >= 0) {
                        prophylaxisNormalScores[slot] = verification.score;
                        prophylaxisCandidateExact[slot] = true;
                        prophylaxisAdjustments[slot] = computeProphylaxisAdjustment(
                                prophylaxisThreatScores[slot], verification.score);
                    }
                }
            }
        }
        rootExact = bestScore > origAlpha && bestScore < beta;

        int flag;
        if (bestScore <= origAlpha) flag = TranspositionTable.UPPER_BOUND;
        else if (bestScore >= beta) flag = TranspositionTable.LOWER_BOUND;
        else flag = TranspositionTable.EXACT;
        if (!repetitionTainted[searchPly]) {
            tt.store(board.zobristKey, depth, adjustMateToTT(bestScore, searchPly), flag, bestMove);
        }
        if (searchPly == 0) {
            int selectedProbe = rootExact && !stopRequested
                    ? selectProphylacticRootCandidate(bestMove, bestScore, depth) : -1;
            if (selectedProbe >= 0) {
                iterationBestMove = prophylaxisMoves[selectedProbe];
                iterationReportedScore = prophylaxisNormalScores[selectedProbe];
                updateProphylaxisDiagnostics(selectedProbe, depth);
                return bestScore;
            }
            iterationBestMove = bestMove;
            iterationReportedScore = bestScore;
            finishProphylaxisDiagnostics(bestMove, bestScore, depth);
        }

        return bestScore;
    }

    private boolean isProphylaxisRootEligible(Board board, boolean inCheck) {
        // Quietness is assessed per candidate below. A single unrelated
        // capture or checking move must not suppress all prophylaxis analysis.
        return !inCheck && board.pieceBB[WHITE][QUEEN] != 0
                && board.pieceBB[BLACK][QUEEN] != 0;
    }

    private void beginProphylaxisRoot(Board board, int depth, int defenderColor) {
        prophylaxisDepth = depth;
        prophylaxisCandidatesProbed = 0;
        prophylaxisLeaves = 0;
        prophylaxisOrderingEvaluations = 0;
        prophylaxisPendingCount = 0;
        prophylaxisCheckedThreats = 0;
        prophylaxisVerificationNodes = 0;
        prophylaxisEvaluations = 0;
        prophylaxisBaselineReady = false;
        prophylaxisBaselineScore = INFINITY_SCORE;
        if (!shouldAbortProphylaxisWork()) {
            prophylaxisBaselineMetrics = Evaluator.threatMetrics(board, defenderColor);
        }

        // Baseline: the root side passes once, then the opponent gets its
        // first action. The probe itself inserts a second defender pass before
        // the opponent's continuation.
        board.makeNullMove();
        try {
            if (!shouldAbortProphylaxisWork() && !board.isInCheck(board.sideToMove)) {
                ThreatProbeResult baseline = probeOpponentTwoActions(board, defenderColor,
                        PROPHYLAXIS_BASELINE_EVALUATIONS);
                if (baseline.evaluations > 0
                        && baseline.rootScore != INFINITY_SCORE) {
                    prophylaxisBaselineScore = baseline.rootScore;
                    prophylaxisBaselineReady = true;
                }
            }
        } finally {
            board.unmakeNullMove();
        }
    }

    private void probeProphylaxisCandidate(Board board, int move, int normalScore,
                                            int depth, int defenderColor) {
        probeProphylaxisCandidate(board, move, normalScore, depth, defenderColor, true);
    }

    private void probeProphylaxisCandidate(Board board, int move, int normalScore,
                                            int depth, int defenderColor, boolean exact) {
        int remaining = PROPHYLAXIS_MAX_EVALUATIONS - prophylaxisEvaluations;
        int budget = Math.min(PROPHYLAXIS_CANDIDATE_EVALUATIONS, remaining);
        if (budget <= 0) return;
        if (shouldAbortProphylaxisWork()) return;
        int beforeLeaves = prophylaxisLeaves;
        long metrics = Evaluator.threatMetrics(board, defenderColor);
        if (shouldAbortProphylaxisWork()) return;
        ThreatProbeResult result = probeOpponentTwoActions(board, defenderColor, budget);
        if (result.evaluations == 0 || result.rootScore == INFINITY_SCORE) return;

        int slot = prophylaxisCandidatesProbed++;
        prophylaxisMoves[slot] = move;
        prophylaxisNormalScores[slot] = normalScore;
        prophylaxisCandidateExact[slot] = exact;
        prophylaxisThreatScores[slot] = result.rootScore;
        prophylaxisMetrics[slot] = metrics;
        prophylaxisLines[slot] = result.line;
        int adjustment = computeProphylaxisAdjustment(result.rootScore, normalScore);
        prophylaxisAdjustments[slot] = adjustment;
        if (prophylaxisCandidateListener != null) {
            prophylaxisCandidateListener.onCandidate(depth, Move.toUci(move), normalScore,
                    result.rootScore, adjustment, metrics, result.line,
                    result.evaluations);
        }
    }

    private int computeProphylaxisAdjustment(int threatScore, int normalScore) {
        int raw = (threatScore - normalScore) / 4;
        return Math.max(-PROPHYLAXIS_MAX_ADJUSTMENT,
                Math.min(PROPHYLAXIS_MAX_ADJUSTMENT, raw));
    }

    private void rememberProphylaxisAlternative(int move, int upperBound) {
        int slot = 0;
        while (slot < prophylaxisPendingCount
                && (prophylaxisPendingScores[slot] > upperBound
                    || (prophylaxisPendingScores[slot] == upperBound
                        && prophylaxisPendingMoves[slot] < move))) {
            slot++;
        }
        if (slot >= PROPHYLAXIS_MAX_PENDING_ALTERNATIVES) return;
        int end = Math.min(prophylaxisPendingCount,
                PROPHYLAXIS_MAX_PENDING_ALTERNATIVES - 1);
        for (int i = end; i > slot; i--) {
            prophylaxisPendingMoves[i] = prophylaxisPendingMoves[i - 1];
            prophylaxisPendingScores[i] = prophylaxisPendingScores[i - 1];
        }
        prophylaxisPendingMoves[slot] = move;
        prophylaxisPendingScores[slot] = upperBound;
        if (prophylaxisPendingCount < PROPHYLAXIS_MAX_PENDING_ALTERNATIVES) {
            prophylaxisPendingCount++;
        }
    }

    private int findProphylaxisCandidate(int move) {
        for (int i = 0; i < prophylaxisCandidatesProbed; i++) {
            if (prophylaxisMoves[i] == move) return i;
        }
        return -1;
    }

    private void ensureBestProphylaxisCandidate(Board board, int bestMove,
                                                 int bestScore, int depth, int moverColor) {
        if (findProphylaxisCandidate(bestMove) >= 0
                || prophylaxisCandidatesProbed >= PROPHYLAXIS_MAX_CANDIDATES
                || Move.isPromotion(bestMove) || shouldAbortProphylaxisWork()) return;
        board.makeMove(bestMove);
        try {
            if (!board.isInCheck(moverColor)
                    && !board.isInCheck(board.sideToMove)) {
                probeProphylaxisCandidate(board, bestMove, bestScore, depth, moverColor);
            }
        } finally {
            board.unmakeMove();
        }
    }

    private void probePromisingPendingAlternatives(Board board, int normalBestMove,
                                                   int normalBestScore, int depth,
                                                   int moverColor) {
        int probed = 0;
        for (int i = 0; i < prophylaxisPendingCount
                && probed < PROPHYLAXIS_MAX_PENDING_PROBES
                && prophylaxisCandidatesProbed < PROPHYLAXIS_MAX_CANDIDATES
                && prophylaxisEvaluations < PROPHYLAXIS_MAX_EVALUATIONS; i++) {
            int move = prophylaxisPendingMoves[i];
            int upperBound = prophylaxisPendingScores[i];
            if (move == normalBestMove || findProphylaxisCandidate(move) >= 0
                    || upperBound < normalBestScore - PROPHYLAXIS_NORMAL_SCORE_BAND
                    || Move.isPromotion(move) || shouldAbortProphylaxisWork()) continue;
            board.makeMove(move);
            try {
                if (board.isInCheck(moverColor) || board.isInCheck(board.sideToMove)) continue;
                probeProphylaxisCandidate(board, move, upperBound, depth, moverColor, false);
                if (findProphylaxisCandidate(move) >= 0) probed++;
            } finally {
                board.unmakeMove();
            }
        }
    }

    private static final class RootVerification {
        final int move;
        final int score;
        final boolean completed;

        RootVerification(int move, int score, boolean completed) {
            this.move = move;
            this.score = score;
            this.completed = completed;
        }
    }

    private RootVerification verifyBestProphylaxisAlternative(Board board, int depth,
                                                               int normalBestMove,
                                                               int normalBestScore,
                                                               int moverColor) {
        int alternative = Move.NONE;
        int alternativeSlot = -1;
        int bestAdjusted = normalBestScore;
        int normalSlot = findProphylaxisCandidate(normalBestMove);
        if (normalSlot >= 0 && prophylaxisCandidateExact[normalSlot]) {
            bestAdjusted += prophylaxisAdjustments[normalSlot];
        }
        int bestAlternativeAdjusted = bestAdjusted;
        for (int i = 0; i < prophylaxisPendingCount; i++) {
            if (prophylaxisPendingScores[i] < normalBestScore
                    - PROPHYLAXIS_NORMAL_SCORE_BAND) continue;
            int move = prophylaxisPendingMoves[i];
            int slot = findProphylaxisCandidate(move);
            if (slot < 0 || prophylaxisCandidateExact[slot]) continue;
            int adjustedUpperBound = prophylaxisPendingScores[i]
                    + prophylaxisAdjustments[slot];
            if (adjustedUpperBound > bestAlternativeAdjusted) {
                alternative = move;
                alternativeSlot = slot;
                bestAlternativeAdjusted = adjustedUpperBound;
            }
        }
        if (alternative == Move.NONE || alternativeSlot < 0
                || shouldAbortProphylaxisWork()) return null;

        board.makeMove(alternative);
        if (board.isInCheck(moverColor)) {
            board.unmakeMove();
            return null;
        }
        long beforeNodes = nodes;
        int verifiedScore;
        try {
            int requiredNormalScore = bestAdjusted
                    - prophylaxisAdjustments[alternativeSlot];
            int thresholdChildScore = negamax(board, depth - 1,
                    -INFINITY_SCORE, -requiredNormalScore, 1, true);
            verifiedScore = -thresholdChildScore;
            if (!stopRequested && !shouldAbortProphylaxisWork()
                    && verifiedScore > requiredNormalScore) {
                verifiedScore = -negamax(board, depth - 1,
                        -INFINITY_SCORE, INFINITY_SCORE, 1, true);
            } else {
                return new RootVerification(alternative, verifiedScore, false);
            }
        } finally {
            board.unmakeMove();
            prophylaxisVerificationNodes += (int) Math.min(Integer.MAX_VALUE,
                    Math.max(0L, nodes - beforeNodes));
        }
        if (stopRequested || shouldAbortProphylaxisWork()) {
            return new RootVerification(alternative, verifiedScore, false);
        }
        return new RootVerification(alternative, verifiedScore, true);
    }

    private boolean shouldAbortProphylaxisWork() {
        if (stopRequested) return true;
        if (timeLimited && checkTime()) {
            stopRequested = true;
            return true;
        }
        return false;
    }

    private int selectProphylacticRootCandidate(int normalBestMove, int normalBestScore,
                                                 int depth) {
        if (!prophylaxisProbeEnabled || !prophylaxisBaselineReady
                || prophylaxisDepth != depth || prophylaxisCandidatesProbed == 0
                || normalBestScore <= -MATE_SCORE + MAX_PLY
                || normalBestScore >= MATE_SCORE - MAX_PLY) return -1;

        int selected = -1;
        int selectedRank = normalBestScore;
        for (int i = 0; i < prophylaxisCandidatesProbed; i++) {
            if (!prophylaxisCandidateExact[i]) continue;
            if (prophylaxisMoves[i] == normalBestMove) {
                selected = i;
                selectedRank = normalBestScore + prophylaxisAdjustments[i];
                break;
            }
        }
        for (int i = 0; i < prophylaxisCandidatesProbed; i++) {
            if (!prophylaxisCandidateExact[i]) continue;
            if (prophylaxisNormalScores[i] < normalBestScore - PROPHYLAXIS_NORMAL_SCORE_BAND) {
                continue;
            }
            int adjusted = prophylaxisNormalScores[i] + prophylaxisAdjustments[i];
            if (adjusted > selectedRank
                    || (adjusted == selectedRank && selected >= 0
                        && prophylaxisNormalScores[i] > prophylaxisNormalScores[selected])) {
                selected = i;
                selectedRank = adjusted;
            }
        }
        return selected >= 0 && prophylaxisMoves[selected] != normalBestMove ? selected : -1;
    }

    private void finishProphylaxisDiagnostics(int move, int normalScore, int depth) {
        if (!prophylaxisProbeEnabled || prophylaxisDepth != depth) return;
        int selected = -1;
        for (int i = 0; i < prophylaxisCandidatesProbed; i++) {
            if (prophylaxisMoves[i] == move) {
                selected = i;
                break;
            }
        }
        if (selected >= 0) updateProphylaxisDiagnostics(selected, depth);
        else lastProphylaxisDiagnostics = new ProphylaxisDiagnostics(depth,
                prophylaxisBaselineReady ? prophylaxisBaselineScore : INFINITY_SCORE,
                prophylaxisBaselineMetrics, prophylaxisCandidatesProbed,
                prophylaxisLeaves, prophylaxisOrderingEvaluations,
                prophylaxisVerificationNodes, prophylaxisCheckedThreats, Move.toUci(move),
                normalScore, INFINITY_SCORE, 0, 0L, "");
    }

    private void updateProphylaxisDiagnostics(int selected, int depth) {
        lastProphylaxisDiagnostics = new ProphylaxisDiagnostics(depth,
                prophylaxisBaselineReady ? prophylaxisBaselineScore : INFINITY_SCORE,
                prophylaxisBaselineMetrics, prophylaxisCandidatesProbed,
                prophylaxisLeaves, prophylaxisOrderingEvaluations,
                prophylaxisVerificationNodes, prophylaxisCheckedThreats,
                Move.toUci(prophylaxisMoves[selected]), prophylaxisNormalScores[selected],
                prophylaxisThreatScores[selected], prophylaxisAdjustments[selected],
                prophylaxisMetrics[selected], prophylaxisLines[selected]);
    }

    static final class ThreatProbeResult {
        final int rootScore;
        final int evaluations;
        final int leaves;
        final int orderedFirstMoves;
        final int checkedThreats;
        final String line;

        ThreatProbeResult(int rootScore, int evaluations, int leaves,
                          int orderedFirstMoves, int checkedThreats, String line) {
            this.rootScore = rootScore;
            this.evaluations = evaluations;
            this.leaves = leaves;
            this.orderedFirstMoves = orderedFirstMoves;
            this.checkedThreats = checkedThreats;
            this.line = line;
        }
    }

    /**
     * Estimates an opponent plan by allowing two opponent moves separated by
     * an artificial pass. Checking first moves are followed through bounded
     * legal defender evasions and attacker continuations; quiet first moves
     * use the artificial-pass model. It performs static leaf evaluation only:
     * no recursive search and no access to TT, repetition, killer, or history.
     */
    private ThreatProbeResult probeOpponentTwoActions(Board board, int defenderColor,
                                                       int maxEvaluations) {
        int startEvaluations = prophylaxisEvaluations;
        if (maxEvaluations <= 0 || board.sideToMove != opposite(defenderColor)) {
            return new ThreatProbeResult(INFINITY_SCORE, 0, 0, 0, 0, "");
        }
        int attacker = board.sideToMove;
        int firstCount = orderProphylaxisMoves(board, defenderColor, 0,
                PROPHYLAXIS_FIRST_MOVE_LIMIT, true, maxEvaluations, startEvaluations);
        int checkedBefore = prophylaxisCheckedThreats;
        int leaves = 0;
        int worstRootScore = INFINITY_SCORE;
        String worstLine = "";

        for (int i = 0; i < firstCount
                && prophylaxisEvaluations - startEvaluations < maxEvaluations; i++) {
            if (shouldAbortProphylaxisWork()) break;
            int first = prophylaxisOrderedMoves[0][i];
            board.makeMove(first);
            try {
                if (board.isInCheck(attacker)) continue;
                boolean givesCheck = board.isInCheck(defenderColor);
                if (givesCheck) {
                    prophylaxisCheckedThreats++;
                    int evasionCount = orderProphylaxisMoves(board, defenderColor, 1,
                            PROPHYLAXIS_EVASION_LIMIT, true, maxEvaluations,
                            startEvaluations);
                    if (evasionCount == 0 && shouldAbortProphylaxisWork()) break;
                    if (evasionCount == 0) {
                        int score = -MATE_SCORE + 2;
                        String line = "CHECK " + Move.toUci(first) + " #";
                        notifyProphylaxisPlan(line, score);
                        if (score < worstRootScore) {
                            worstRootScore = score;
                            worstLine = line;
                        }
                        continue;
                    }
                    int bestDefenseScore = -INFINITY_SCORE;
                    String bestDefenseLine = "";
                    boolean completedCheckBranch = true;
                    int processedEvasions = 0;
                    for (int e = 0; e < evasionCount; e++) {
                        if (prophylaxisEvaluations - startEvaluations >= maxEvaluations
                                || prophylaxisEvaluations >= PROPHYLAXIS_MAX_EVALUATIONS) {
                            completedCheckBranch = false;
                            break;
                        }
                        if (shouldAbortProphylaxisWork()) {
                            completedCheckBranch = false;
                            break;
                        }
                        int evasion = prophylaxisOrderedMoves[1][e];
                        board.makeMove(evasion);
                        try {
                            if (board.isInCheck(defenderColor)) continue;
                            int continuationCount = orderProphylaxisMoves(board, defenderColor, 2,
                                    PROPHYLAXIS_CONTINUATION_LIMIT, false, maxEvaluations,
                                    startEvaluations);
                            if (continuationCount == 0 && shouldAbortProphylaxisWork()) {
                                completedCheckBranch = false;
                                break;
                            }
                            if (continuationCount == 0) {
                                int score = board.isInCheck(attacker)
                                        ? MATE_SCORE - 3 : 0;
                                String line = "CHECK " + Move.toUci(first) + " "
                                        + Move.toUci(evasion) + (score == 0
                                                ? " (stalemate)" : " #");
                                if (score > bestDefenseScore) {
                                    bestDefenseScore = score;
                                    bestDefenseLine = line;
                                }
                                processedEvasions++;
                                continue;
                            }
                            int bestAttackContinuation = INFINITY_SCORE;
                            String bestContinuationLine = "";
                            int processedContinuations = 0;
                            for (int c = 0; c < continuationCount; c++) {
                                if (prophylaxisEvaluations - startEvaluations >= maxEvaluations
                                        || prophylaxisEvaluations >= PROPHYLAXIS_MAX_EVALUATIONS) {
                                    completedCheckBranch = false;
                                    break;
                                }
                                if (shouldAbortProphylaxisWork()) {
                                    completedCheckBranch = false;
                                    break;
                                }
                                int continuation = prophylaxisOrderedMoves[2][c];
                                board.makeMove(continuation);
                                try {
                                    if (board.isInCheck(attacker)
                                            || !consumeProphylaxisEvaluation(maxEvaluations,
                                                    startEvaluations)) continue;
                                    int score = Evaluator.evaluate(board);
                                    prophylaxisLeaves++;
                                    leaves++;
                                    String line = "CHECK " + Move.toUci(first) + " "
                                            + Move.toUci(evasion) + " "
                                            + Move.toUci(continuation);
                                    notifyProphylaxisPlan(line, score);
                                    if (score < bestAttackContinuation) {
                                        bestAttackContinuation = score;
                                        bestContinuationLine = line;
                                    }
                                    processedContinuations++;
                                } finally {
                                    board.unmakeMove();
                                }
                            }
                            if (processedContinuations != continuationCount) {
                                completedCheckBranch = false;
                            }
                            if (bestAttackContinuation == INFINITY_SCORE) {
                                completedCheckBranch = false;
                            }
                            if (bestAttackContinuation != INFINITY_SCORE
                                    && bestAttackContinuation > bestDefenseScore) {
                                bestDefenseScore = bestAttackContinuation;
                                bestDefenseLine = bestContinuationLine;
                            }
                            if (completedCheckBranch) processedEvasions++;
                        } finally {
                            board.unmakeMove();
                        }
                    }
                    if (processedEvasions != evasionCount) completedCheckBranch = false;
                    if (completedCheckBranch && bestDefenseScore != -INFINITY_SCORE) {
                        notifyProphylaxisPlan(bestDefenseLine, bestDefenseScore);
                        if (bestDefenseScore < worstRootScore) {
                            worstRootScore = bestDefenseScore;
                            worstLine = bestDefenseLine;
                        }
                    }
                    continue;
                }

                if (!MoveGenerator.hasLegalMove(board)) continue; // stalemate
                board.makeNullMove();
                try {
                    // The pass represents a hypothetical missed defensive
                    // move and is only legal as a model when not in check.
                    if (board.isInCheck(defenderColor)) continue;
                    int continuationCount = orderProphylaxisMoves(board, defenderColor, 2,
                            PROPHYLAXIS_CONTINUATION_LIMIT, false, maxEvaluations,
                            startEvaluations);
                    if (continuationCount == 0 && shouldAbortProphylaxisWork()) continue;
                    if (continuationCount == 0) {
                        int score = board.isInCheck(attacker) ? MATE_SCORE - 2 : 0;
                        String line = "PASS " + Move.toUci(first)
                                + (score == 0 ? " (stalemate)" : " #");
                        notifyProphylaxisPlan(line, score);
                        if (score < worstRootScore) {
                            worstRootScore = score;
                            worstLine = line;
                        }
                    }
                    int bestContinuationScore = INFINITY_SCORE;
                    String bestContinuationLine = "";
                    int processedContinuations = 0;
                    for (int c = 0; c < continuationCount; c++) {
                        if (prophylaxisEvaluations - startEvaluations >= maxEvaluations
                                || prophylaxisEvaluations >= PROPHYLAXIS_MAX_EVALUATIONS) break;
                        if (shouldAbortProphylaxisWork()) break;
                        int continuation = prophylaxisOrderedMoves[2][c];
                        board.makeMove(continuation);
                        try {
                            if (board.isInCheck(attacker)
                                    || !consumeProphylaxisEvaluation(maxEvaluations,
                                            startEvaluations)) continue;
                            int score = Evaluator.evaluate(board);
                            prophylaxisLeaves++;
                            leaves++;
                            String line = "PASS " + Move.toUci(first) + " "
                                    + Move.toUci(continuation);
                            notifyProphylaxisPlan(line, score);
                            if (score < bestContinuationScore) {
                                bestContinuationScore = score;
                                bestContinuationLine = line;
                            }
                            processedContinuations++;
                        } finally {
                            board.unmakeMove();
                        }
                    }
                    if (processedContinuations == continuationCount
                            && bestContinuationScore != INFINITY_SCORE
                            && bestContinuationScore < worstRootScore) {
                        worstRootScore = bestContinuationScore;
                        worstLine = bestContinuationLine;
                    }
                } finally {
                    board.unmakeNullMove();
                }
            } finally {
                board.unmakeMove();
            }
        }
        return new ThreatProbeResult(worstRootScore,
                prophylaxisEvaluations - startEvaluations, leaves, firstCount,
                prophylaxisCheckedThreats - checkedBefore, worstLine);
    }

    private int orderProphylaxisMoves(Board board, int defenderColor, int level,
                                      int limit, boolean staticOrder,
                                      int maxEvaluations, int evaluationStart) {
        if (limit <= 0 || shouldAbortProphylaxisWork()) return 0;
        int moverColor = board.sideToMove;
        MoveList pseudo = prophylaxisMoveLists[level];
        pseudo.clear();
        MoveGenerator.generatePseudoLegal(board, pseudo, false);
        int count = 0;
        int legalScanned = 0;
        int[] orderedMoves = prophylaxisOrderedMoves[level];
        int[] orderedScores = prophylaxisOrderedScores[level];
        for (int i = 0; i < pseudo.size && legalScanned < PROPHYLAXIS_MOVE_BUFFER; i++) {
            if (shouldAbortProphylaxisWork()) break;
            int move = pseudo.get(i);
            int captureScore = Move.isCapture(move) ? mvvLva(board, move) : 0;
            board.makeMove(move);
            int priority;
            boolean legal;
            try {
                legal = !board.isInCheck(moverColor);
                if (!legal) continue;
                legalScanned++;
                priority = prophylaxisMovePriority(board, move, moverColor,
                        defenderColor, captureScore);
            } finally {
                board.unmakeMove();
            }
            count = insertOrderedProphylaxisMove(orderedMoves, orderedScores,
                    count, limit, move, priority);
        }

        if (staticOrder) {
            int[] leafScores = prophylaxisOrderedLeafScores[level];
            for (int i = 0; i < count; i++) {
                if (prophylaxisEvaluations - evaluationStart >= maxEvaluations
                        || prophylaxisEvaluations >= PROPHYLAXIS_MAX_EVALUATIONS
                        || shouldAbortProphylaxisWork()) break;
                int move = orderedMoves[i];
                board.makeMove(move);
                try {
                    if (board.isInCheck(moverColor)) continue;
                    int eval = Evaluator.evaluate(board);
                    prophylaxisEvaluations++;
                    prophylaxisOrderingEvaluations++;
                    leafScores[i] = eval;
                    int moverPerspective = -eval;
                    orderedScores[i] += Math.max(-3000, Math.min(3000, moverPerspective));
                } finally {
                    board.unmakeMove();
                }
            }
            sortOrderedProphylaxisMoves(orderedMoves, orderedScores, leafScores, count);
        }
        return count;
    }

    private int prophylaxisMovePriority(Board afterMove, int move, int moverColor,
                                        int defenderColor, int captureScore) {
        int priority = 0;
        boolean check = moverColor != defenderColor && afterMove.isInCheck(defenderColor);
        if (check) {
            boolean safe = !afterMove.isSquareAttacked(Move.to(move), defenderColor);
            priority += safe ? 100000 : 90000;
        }
        if (captureScore != 0) priority += 20000 + captureScore;
        if (Move.isPromotion(move)) priority += 40000;
        if (moverColor == defenderColor
                && afterMove.pieceTypeAt(Move.to(move)) == KING) priority += 15000;

        int from = Move.from(move);
        int to = Move.to(move);
        if (afterMove.pieceTypeAt(to) == PAWN && moverColor != defenderColor) {
            int king = afterMove.kingSquare(defenderColor);
            int kingFile = king & 7;
            int toFile = to & 7;
            int fromRankDistance = Math.abs((from >>> 3) - (king >>> 3));
            int toRankDistance = Math.abs((to >>> 3) - (king >>> 3));
            if (Math.abs(toFile - kingFile) <= 1 && toRankDistance < fromRankDistance) {
                priority += 12000;
            }
        }
        return priority;
    }

    private int insertOrderedProphylaxisMove(int[] moves, int[] scores, int count,
                                             int limit, int move, int score) {
        int slot = 0;
        while (slot < count && (scores[slot] > score
                || (scores[slot] == score && moves[slot] < move))) slot++;
        if (slot >= limit) return count;
        int newCount = Math.min(limit, count + 1);
        for (int i = newCount - 1; i > slot; i--) {
            moves[i] = moves[i - 1];
            scores[i] = scores[i - 1];
        }
        moves[slot] = move;
        scores[slot] = score;
        return newCount;
    }

    private void sortOrderedProphylaxisMoves(int[] moves, int[] scores,
                                              int[] leafScores, int count) {
        for (int i = 1; i < count; i++) {
            int move = moves[i];
            int score = scores[i];
            int leaf = leafScores[i];
            int j = i;
            while (j > 0 && (scores[j - 1] < score
                    || (scores[j - 1] == score && moves[j - 1] > move))) {
                moves[j] = moves[j - 1];
                scores[j] = scores[j - 1];
                leafScores[j] = leafScores[j - 1];
                j--;
            }
            moves[j] = move;
            scores[j] = score;
            leafScores[j] = leaf;
        }
    }

    private boolean consumeProphylaxisEvaluation(int localLimit, int localStart) {
        if (shouldAbortProphylaxisWork()
                || prophylaxisEvaluations - localStart >= localLimit
                || prophylaxisEvaluations >= PROPHYLAXIS_MAX_EVALUATIONS) return false;
        prophylaxisEvaluations++;
        return true;
    }

    private void notifyProphylaxisPlan(String line, int score) {
        if (prophylaxisPlanListener != null) prophylaxisPlanListener.onPlan(line, score);
    }

    ThreatProbeResult probeOpponentTwoActionsForTesting(Board board, int defenderColor,
                                                          int evaluationBudget) {
        prophylaxisEvaluations = 0;
        prophylaxisLeaves = 0;
        prophylaxisOrderingEvaluations = 0;
        prophylaxisCheckedThreats = 0;
        return probeOpponentTwoActions(board, defenderColor, evaluationBudget);
    }

    static String formatThreatMetrics(long metrics) {
        return "safeChecks=" + Evaluator.threatSafeCheckCount(metrics)
                + ",kingPressure=" + Evaluator.threatKingPressure(metrics)
                + ",pawnBreaks=" + Evaluator.threatPawnBreakCount(metrics)
                + ",openKingFiles=" + Evaluator.threatOpenKingFileCount(metrics)
                + ",semiOpenKingFiles=" + Evaluator.threatSemiOpenKingFileCount(metrics);
    }

    /**
     * Keeps the first four ordered legal moves at nominal depth. Later quiet
     * moves get a one-ply scout, with deeper reductions as both depth and rank
     * increase. This preserves the baseline search breadth before concentrating
     * effort on the highest-ranked candidates.
     */
    static int lateMoveReduction(int depth, int moveRank) {
        if (depth < 3 || moveRank <= 4) return 0;
        int reduction = 1;
        if (depth >= 4 && moveRank >= 6) {
            int extra = ((depth - 3) * (moveRank - 5)) / 20;
            reduction += Math.min(2, extra);
        }
        return Math.min(reduction, Math.max(0, depth - 2));
    }

    int lmrReductionForMove(int move, int depth, int moveRank, int searchPly,
                            int ttMove, boolean inCheck, boolean givesCheck,
                            boolean advancedPasser) {
        if (!lmrEnabled
                || inCheck || givesCheck || advancedPasser
                || Move.isCapture(move) || Move.isPromotion(move)
                || move == ttMove || searchPly < 0 || searchPly >= MAX_PLY
                || move == killerMoves[searchPly][0] || move == killerMoves[searchPly][1]) {
            return 0;
        }
        return lateMoveReduction(depth, moveRank);
    }

    int lmrReductionForMove(int move, int depth, int moveRank, int searchPly,
                            int ttMove, boolean inCheck, boolean givesCheck,
                            boolean advancedPasser, Board positionAfterMove, int moverColor) {
        if (isNearRootPawnOffer(positionAfterMove, move, moverColor, searchPly)) return 0;
        return lmrReductionForMove(move, depth, moveRank, searchPly,
                ttMove, inCheck, givesCheck, advancedPasser);
    }

    /**
     * A quiet pawn move near the root that can be taken by an enemy pawn is
     * searched at nominal depth. This gives sound gambits such as 2.c4 their
     * tactical verification while also making unsound pawn offers visible to
     * the same full-depth search. The two-ply bound keeps the extra work local.
     * The board must be in the position immediately after {@code move}.
     */
    boolean isNearRootPawnOffer(Board positionAfterMove, int move, int moverColor,
                                int searchPly) {
        if (searchPly > 1 || searchPly < 0 || Move.isCapture(move)
                || Move.isPromotion(move) || positionAfterMove.pieceTypeAt(Move.to(move)) != PAWN) {
            return false;
        }
        int enemy = opposite(moverColor);
        return (Bitboards.PAWN_ATTACKS[moverColor][Move.to(move)]
                & positionAfterMove.pieceBB[enemy][PAWN]) != 0;
    }

    boolean isAdvancedPassedPawnPush(Board positionAfterMove, int move, int moverColor) {
        int to = Move.to(move);
        if (positionAfterMove.pieceTypeAt(to) != PAWN
                || !Evaluator.isPassedPawn(positionAfterMove, moverColor, to)) return false;
        int rank = to >>> 3;
        return moverColor == WHITE ? rank >= 4 : rank <= 3;
    }

    private int quiescence(Board board, int alpha, int beta, int searchPly) {
        if ((nodes & 2047) == 0 && checkTime()) stopRequested = true;
        if (stopRequested) return 0;
        nodes++;
        if (instrumentationEnabled) quiescenceNodes++;
        observeSelectiveDepth(searchPly);

        repetitionTainted[searchPly] = false;

        if (searchPly >= MAX_PLY - 1) {
            if (instrumentationEnabled) evaluatorCalls++;
            return Evaluator.evaluate(board);
        }

        if (keyStack != null) {
            int absPly = historyBase + searchPly;
            keyStack[absPly] = board.zobristKey;
            int repetitions = 0;
            for (int p = absPly - 2; p >= 0; p -= 2) {
                if (keyStack[p] == board.zobristKey) {
                    repetitions++;
                    repetitionTainted[searchPly] = true;
                    if (isRepetitionDraw(repetitions, searchPly)) {
                        if (instrumentationEnabled) repetitionExits++;
                        return 0;
                    }
                }
            }
        }

        boolean inCheck = board.isInCheck(board.sideToMove);
        int standPat = -INFINITY_SCORE;
        if (!inCheck) {
            // Quiescence can be entered immediately after a move that leaves
            // the opponent with no legal moves. Without this terminal check,
            // a stalemate at the horizon is incorrectly scored by stand-pat.
            if (!MoveGenerator.hasLegalKingMove(board)
                    && !MoveGenerator.hasLegalPawnMove(board, moveLists[searchPly])
                    && !MoveGenerator.hasLegalMove(board)) return 0;
            if (instrumentationEnabled) evaluatorCalls++;
            standPat = Evaluator.evaluate(board);
            if (standPat >= beta) {
                if (instrumentationEnabled) quiescenceBetaCutoffs++;
                return beta;
            }
            if (standPat > alpha) alpha = standPat;
        }

        MoveList moves = moveLists[searchPly];
        moves.clear();
        MoveGenerator.generatePseudoLegal(board, moves, !inCheck);
        int[] scores = scoreBuffer(searchPly, moves.size);
        if (inCheck) scoreMoves(board, moves, Move.NONE, searchPly, scores);
        else scoreCaptures(board, moves, scores);

        int us = board.sideToMove;
        int legalCount = 0;
        for (int i = 0; i < moves.size; i++) {
            int bestIdx = i;
            for (int j = i + 1; j < moves.size; j++) if (scores[j] > scores[bestIdx]) bestIdx = j;
            if (bestIdx != i) {
                int tmpM = moves.moves[i]; moves.moves[i] = moves.moves[bestIdx]; moves.moves[bestIdx] = tmpM;
                int tmpS = scores[i]; scores[i] = scores[bestIdx]; scores[bestIdx] = tmpS;
            }
            int move = moves.get(i);

            // A capture that cannot lift alpha need not be searched unless it gives check.
            // Promotions, en passant, and king captures have tactical edge cases, so keep them.
            boolean prune = false;
            if (!inCheck && !Move.isPromotion(move) && !Move.isEnPassant(move)
                    && board.pieceTypeAt(Move.from(move)) != KING
                    && Math.abs(alpha) < MATE_SCORE - MAX_PLY) {
                int victim = board.pieceTypeAt(Move.to(move));
                prune = standPat + PIECE_VALUE[victim] + 200 < alpha;
                // A cheaper attacker cannot lose material on this square: the
                // opponent can take at most that attacker before we may stop.
                if (!prune && PIECE_VALUE[board.pieceTypeAt(Move.from(move))] > PIECE_VALUE[victim]) {
                    int see = StaticExchange.evaluate(board, move, seeGains[searchPly]);
                    if (instrumentationEnabled) {
                        seeCalls++;
                        qPruneSeeCalls++;
                    }
                    prune = see < 0;
                }
            }

            board.makeMove(move);
            if (board.isInCheck(us)) {
                board.unmakeMove();
                continue;
            }
            legalCount++;
            if (prune && !board.isInCheck(board.sideToMove)) {
                board.unmakeMove();
                continue;
            }
            if (instrumentationEnabled) legalMovesSearched++;
            int score = -quiescence(board, -beta, -alpha, searchPly + 1);
            board.unmakeMove();
            if (repetitionTainted[searchPly + 1]) repetitionTainted[searchPly] = true;
            if (stopRequested) return 0;

            if (score >= beta) {
                if (instrumentationEnabled) quiescenceBetaCutoffs++;
                return beta;
            }
            if (score > alpha) alpha = score;
        }
        if (inCheck && legalCount == 0) return -(MATE_SCORE - searchPly);
        return alpha;
    }

    private int[] scoreBuffer(int searchPly, int requiredSize) {
        int[] scores = moveScores[searchPly];
        if (requiredSize > scores.length) {
            int newSize = scores.length;
            while (newSize < requiredSize) newSize *= 2;
            scores = Arrays.copyOf(scores, newSize);
            moveScores[searchPly] = scores;
        }
        return scores;
    }

    private void observeSelectiveDepth(int searchPly) {
        int reached = searchPly + 1;
        if (reached > selDepth) selDepth = reached;
        if (reached > maxSelectiveDepth) maxSelectiveDepth = reached;
    }

    private void scoreMoves(Board board, MoveList moves, int ttMove,
                            int searchPly, int[] scores) {
        for (int i = 0; i < moves.size; i++) {
            int move = moves.get(i);
            if (move == ttMove) scores[i] = 2_000_000;
            else if (Move.isCapture(move)) scores[i] = 1_000_000 + mvvLva(board, move);
            else if (Move.isPromotion(move)) scores[i] = 900_000 + PIECE_VALUE[Move.promotionPieceType(move)];
            else if (move == killerMoves[searchPly][0]) scores[i] = 800_000;
            else if (move == killerMoves[searchPly][1]) scores[i] = 790_000;
            else scores[i] = historyTable[Move.from(move)][Move.to(move)];
        }
    }

    private void scoreCaptures(Board board, MoveList moves, int[] scores) {
        for (int i = 0; i < moves.size; i++) scores[i] = mvvLva(board, moves.get(i));
    }

    private int maybeDemoteLosingCapture(Board board, MoveList moves, int[] scores,
                                         int bestIdx, int depth, int searchPly) {
        int selected = moves.get(bestIdx);
        if (depth < 5 || !isEligibleMainTieCapture(board, selected)) return bestIdx;
        int rawScore = scores[bestIdx];
        // Pay for SEE only when the legacy selection has selected a capture
        // and a tied capture remains in the suffix.
        boolean hasTiedCapture = false;
        for (int j = bestIdx + 1; j < moves.size; j++) {
            if (scores[j] == rawScore && isEligibleMainTieCapture(board, moves.get(j))) {
                hasTiedCapture = true;
                break;
            }
        }
        if (!hasTiedCapture) return bestIdx;

        int selectedSee = selectiveSeeForTie(board, selected, searchPly);
        if (selectedSee > -300) return bestIdx;
        for (int j = bestIdx + 1; j < moves.size; j++) {
            if (scores[j] != rawScore || !isEligibleMainTieCapture(board, moves.get(j))) continue;
            int candidateSee = selectiveSeeForTie(board, moves.get(j), searchPly);
            if (candidateSee >= 0) {
                if (instrumentationEnabled) mainSeeDemotions++;
                return j;
            }
        }
        return bestIdx;
    }

    private boolean isEligibleMainTieCapture(Board board, int move) {
        if (!Move.isCapture(move) || Move.isPromotion(move) || Move.isEnPassant(move)) return false;
        int attacker = board.pieceTypeAt(Move.from(move));
        int victim = board.pieceTypeAt(Move.to(move));
        return attacker >= 0 && victim >= 0 && PIECE_VALUE[attacker] > PIECE_VALUE[victim];
    }

    private int selectiveSeeForTie(Board board, int move, int searchPly) {
        int attacker = board.pieceTypeAt(Move.from(move));
        int victim = Move.isEnPassant(move) ? PAWN : board.pieceTypeAt(Move.to(move));
        if (attacker < 0 || victim < 0 || PIECE_VALUE[attacker] <= PIECE_VALUE[victim]) return 0;
        int see = StaticExchange.evaluate(board, move, seeGains[searchPly]);
        if (instrumentationEnabled) {
            seeCalls++;
            mainTieBreakSeeCalls++;
        }
        return see;
    }

    private int mvvLva(Board board, int move) {
        int to = Move.to(move);
        int victimType = Move.isEnPassant(move) ? PAWN : board.pieceTypeAt(to);
        int attackerType = board.pieceTypeAt(Move.from(move));
        int victimVal = victimType >= 0 ? PIECE_VALUE[victimType] : PAWN_VALUE;
        int attackerVal = attackerType >= 0 ? PIECE_VALUE[attackerType] : 0;
        int s = victimVal * 16 - attackerVal;
        if (Move.isPromotion(move)) s += PIECE_VALUE[Move.promotionPieceType(move)];
        return s;
    }
}

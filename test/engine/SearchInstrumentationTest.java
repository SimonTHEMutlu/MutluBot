package engine;

/** Focused checks for the low-overhead counters exposed by Search. */
public final class SearchInstrumentationTest {
    public static void main(String[] args) {
        testCountersAndSelectiveDepth();
        testCountersResetBetweenSearches();
        testDisabledAndEnabledResultParity();
        System.out.println("SearchInstrumentationTest passed");
    }

    private static void testCountersAndSelectiveDepth() {
        Board board = new Board();
        Search search = new Search(new TranspositionTable(1));
        search.setInstrumentationEnabled(true);
        final int[] reportedSelectiveDepth = {0};
        search.setInfoListener((depth, selDepth, score, mate, mateIn, nodes, nps, timeMs, pv) -> {
            if (depth == 4) reportedSelectiveDepth[0] = selDepth;
        });

        search.search(board, 4, -1, null);
        Search.SearchStats stats = search.getLastStats();

        check(stats.totalNodes == stats.mainSearchNodes + stats.quiescenceNodes,
                "total nodes must equal main plus quiescence nodes");
        check(stats.mainSearchNodes > 0, "main-search nodes must be counted");
        check(stats.quiescenceNodes > 0, "quiescence nodes must be counted");
        check(stats.evaluatorCalls > 0, "evaluator calls must be counted");
        check(stats.maxSelectiveDepth >= 4, "actual selective depth must reach requested depth");
        check(reportedSelectiveDepth[0] == stats.maxSelectiveDepth,
                "final UCI selective depth must match the search snapshot");
        check(stats.ttProbes > 0 && stats.ttHits <= stats.ttProbes,
                "TT probe/hit counters must be consistent");
        check(stats.ttExactHits <= stats.ttHits && stats.ttMoveAvailable <= stats.ttHits,
                "TT sub-counters must not exceed TT hits");
        check(stats.legalMovesSearched > 0, "legal moves searched must be counted");
        check(stats.betaCutoffs > 0, "beta cutoffs must be counted");
        check(stats.lmrAttempts == stats.lmrReducedSearches,
                "each current LMR attempt performs one reduced search");
        check(stats.lmrFullDepthResearches <= stats.lmrReducedSearches,
                "LMR re-searches cannot exceed reduced searches");
        check(stats.nullMoveCutoffs <= stats.nullMoveAttempts,
                "null-move cutoffs cannot exceed attempts");
        check(stats.seeCalls == stats.mainTieBreakSeeCalls + stats.qPruneSeeCalls,
                "SEE calls must equal main-order plus q-prune calls");
        check(stats.seeCalls > 0, "SEE instrumentation must count selective calls");
        check(stats.mainSeeDemotions <= stats.mainTieBreakSeeCalls,
                "SEE demotions cannot exceed main tie-break SEE calls");
        check(stats.firstMoveCutoffs <= stats.mainSearchBetaCutoffs,
                "first-move cutoffs cannot exceed main-search beta cutoffs");
    }

    private static void testCountersResetBetweenSearches() {
        Board board = new Board();
        Search search = new Search(new TranspositionTable(1));
        search.setInstrumentationEnabled(true);
        search.search(board, 3, -1, null);
        Search.SearchStats first = search.getLastStats();
        check(first.totalNodes > 0, "first search must produce counters");

        Board fiftyMove = new Board();
        fiftyMove.setFromFen("7k/8/8/8/8/8/8/Q6K w - - 100 1");
        search.setInstrumentationEnabled(false);
        search.search(fiftyMove, 1, -1, null);
        Search.SearchStats second = search.getLastStats();
        check(second.totalNodes == 1 && second.mainSearchNodes == 0
                        && second.quiescenceNodes == 0,
                "total nodes must remain available when detailed counters are disabled");
        check(!second.instrumentationEnabled, "disabled search must be marked disabled");
        check(second.fiftyMoveExits == 0 && second.ttProbes == 0,
                "detailed counters must be zero when disabled");
        check(second.evaluatorCalls == 0, "fifty-move draw must not evaluate a position");

        search.setInstrumentationEnabled(true);
        search.search(fiftyMove, 1, -1, null);
        Search.SearchStats third = search.getLastStats();
        check(third.totalNodes == 1 && third.fiftyMoveExits == 1,
                "enabled counters must reset and count the new search only");
    }

    private static void testDisabledAndEnabledResultParity() {
        Board enabledBoard = new Board();
        Search enabled = new Search(new TranspositionTable(1));
        enabled.setInstrumentationEnabled(true);
        final String[] enabledInfo = {""};
        enabled.setInfoListener((depth, selDepth, score, mate, mateIn, nodes, nps, timeMs, pv) -> {
            if (depth == 4) enabledInfo[0] = score + "|" + pv;
        });
        int enabledMove = enabled.search(enabledBoard, 4, -1, null);
        Search.SearchStats enabledStats = enabled.getLastStats();

        Board disabledBoard = new Board();
        Search disabled = new Search(new TranspositionTable(1));
        disabled.setInstrumentationEnabled(false);
        final String[] disabledInfo = {""};
        disabled.setInfoListener((depth, selDepth, score, mate, mateIn, nodes, nps, timeMs, pv) -> {
            if (depth == 4) disabledInfo[0] = score + "|" + pv;
        });
        int disabledMove = disabled.search(disabledBoard, 4, -1, null);
        Search.SearchStats disabledStats = disabled.getLastStats();

        check(enabledMove == disabledMove, "instrumentation must not change best move");
        check(enabledInfo[0].equals(disabledInfo[0]),
                "instrumentation must not change score or PV");
        check(enabledStats.totalNodes == disabledStats.totalNodes,
                "instrumentation must not change node count");
        check(disabledStats.maxSelectiveDepth == enabledStats.maxSelectiveDepth,
                "selective depth must remain accurate when counters are disabled");
        check(!disabledStats.instrumentationEnabled && disabledStats.ttProbes == 0
                        && disabledStats.legalMovesSearched == 0,
                "detailed counters must be zero in disabled mode");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

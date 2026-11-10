package engine;

/** Fresh-JVM pawn-table capacity benchmark; use separate runs for timings and counters. */
public final class PawnTableCapacityBenchmark {
    public static void main(String[] args) {
        int position = Integer.parseInt(args[0]);
        int depth = Integer.parseInt(args[1]);
        int capacity = Integer.parseInt(args[2]);
        boolean countCache = args.length > 3 && Boolean.parseBoolean(args[3]);
        Board board = new Board();
        board.setFromFen(FeedbackBenchmarkMatrix.FENS[position]);
        Search search = new Search(new TranspositionTable(256), capacity);
        search.setProphylaxisProbeEnabled(false);
        search.setInstrumentationEnabled(false);
        search.setPawnCacheInstrumentationEnabled(countCache);
        search.setInfoListener((d, sd, score, mate, mateIn, nodes, nps, ms, pv) -> {
            if (d == depth) System.out.println("RESULT position="
                    + FeedbackBenchmarkMatrix.NAMES[position] + " capacity=" + capacity
                    + " depth=" + d + " seldepth=" + sd + " nodes=" + nodes
                    + " ms=" + ms + " nps=" + nps + " score=" + score + " pv=" + pv);
        });
        int move = search.search(board, depth, -1, null);
        System.out.println("MOVE " + Move.toUci(move));
        if (countCache) {
            Evaluator.PawnCacheStats stats = search.pawnCacheStats();
            System.out.println("PAWN probes=" + stats.probes + " hits=" + stats.hits
                    + " misses=" + stats.misses + " replacements=" + stats.replacements
                    + " hitRate=" + (stats.probes == 0 ? 0
                    : (100.0 * stats.hits / stats.probes)));
        }
    }
}

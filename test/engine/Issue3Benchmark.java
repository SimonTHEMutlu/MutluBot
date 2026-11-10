package engine;

/** Issue 3 comparison: fresh JVM, Hash 256 MB, probe and instrumentation disabled. */
public final class Issue3Benchmark {
    public static void main(String[] args) {
        int depth = Integer.parseInt(args[0]);
        Board board = new Board();
        if (args.length > 1) board.setFromFen(args[1]);
        Search search = new Search(new TranspositionTable(256));
        search.setProphylaxisProbeEnabled(false);
        search.setInstrumentationEnabled(false);
        search.setInfoListener((d, sd, score, mate, mateIn, nodes, nps, ms, pv) -> {
            if (d == depth) System.out.println("depth=" + d + " seldepth=" + sd
                    + " nodes=" + nodes + " ms=" + ms + " nps=" + nps
                    + " score=" + score + " pv=" + pv);
        });
        int move = search.search(board, depth, -1, null);
        System.out.println("bestmove=" + Move.toUci(move));
    }
}

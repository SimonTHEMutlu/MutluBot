package engine;

/** Fresh-process benchmark for legality work; defaults to 256 MB and probe off. */
public final class QsearchLegalityBenchmark {
    public static void main(String[] args) {
        final int depth = Integer.parseInt(args[0]);
        Board board = new Board();
        if (args.length > 1) board.setFromFen(args[1]);
        Search search = new Search(new TranspositionTable(256));
        search.setProphylaxisProbeEnabled(false);
        search.setInstrumentationEnabled(true);
        search.setInfoListener((d, sd, score, mate, mateIn, nodes, nps, ms, pv) -> {
            if (d == depth) System.out.println("depth=" + d + " seldepth=" + sd
                    + " nodes=" + nodes + " ms=" + ms + " nps=" + nps
                    + " score=" + score + " pv=" + pv);
        });
        System.out.println("best=" + Move.toUci(search.search(board, depth, -1, null)));
        System.out.println(search.getLastStats().toSummary());
    }
}

package engine;

/** Small repeatable fixed-depth search benchmark. Run each position in a fresh VM. */
public final class SearchBenchmark {
    public static void main(String[] args) {
        int depth = Integer.parseInt(args[0]);
        Board board = new Board();
        boolean instrumentation = args.length < 2 || !"--disabled".equals(args[args.length - 1]);
        int fenArgCount = instrumentation ? args.length : args.length - 1;
        if (fenArgCount > 1) board.setFromFen(args[1]);
        Search search = new Search(new TranspositionTable(16));
        search.setInstrumentationEnabled(instrumentation);
        search.setInfoListener((d, sd, score, mate, mateIn, nodes, nps, ms, pv) -> {
            if (d == depth) System.out.println("depth=" + d + " nodes=" + nodes
                    + " ms=" + ms + " nps=" + nps + " score=" + score + " pv=" + pv);
        });
        search.search(board, depth, -1, null);
        System.out.println("stats " + search.getLastStats().toSummary());
    }
}

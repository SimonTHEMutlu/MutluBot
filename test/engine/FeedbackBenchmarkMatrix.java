package engine;

/** One matrix search per fresh JVM. Cache switch changes computation only. */
public final class FeedbackBenchmarkMatrix {
    static final String[] NAMES = {"start", "kiwipete", "closed", "open", "rook", "minor"};
    static final int[] DEPTHS = {10, 8, 10, 9, 11, 11};
    static final String[] FENS = {
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        "r1bq1rk1/pp1n1pbp/2pp1np1/4p3/2PPP3/2N2N2/PP2BPPP/R1BQ1RK1 w - - 0 9",
        "r1bqk2r/ppp2ppp/2np1n2/2b1p3/2B1P3/2PP1N2/PP3PPP/RNBQK2R w KQkq - 0 6",
        "8/5pk1/6p1/3r3p/3P3P/4R1P1/5PK1/8 w - - 0 35",
        "8/4kpp1/2n1p2p/3pP3/3P1P2/2N3P1/5K1P/8 w - - 0 32"
    };
    public static void main(String[] args) {
        int index = Integer.parseInt(args[0]);
        int depth = args.length > 1 ? Integer.parseInt(args[1]) : DEPTHS[index];
        // Reflection keeps the identical harness runnable against pre-cache baseline.
        boolean enabled = args.length <= 2 || Boolean.parseBoolean(args[2]);
        boolean stats = args.length > 3 && Boolean.parseBoolean(args[3]);
        Board board = new Board(); board.setFromFen(FENS[index]);
        Search search = new Search(new TranspositionTable(256));
        Object pawnTable = configure(search, enabled, stats);
        search.setProphylaxisProbeEnabled(false);
        search.setInstrumentationEnabled(false);
        search.setInfoListener((d, sd, score, mate, mateIn, nodes, nps, ms, pv) -> {
            if (d == depth) System.out.println("RESULT name=" + NAMES[index] + " depth=" + d
                    + " nodes=" + nodes + " ms=" + ms + " nps=" + nps
                    + " score=" + score + " pv=" + pv);
        });
        int move = search.search(board, depth, -1, null);
        System.out.println("MOVE " + Move.toUci(move));
        if (stats) {
            try {
                for (String field : new String[] {"probes", "hits", "misses", "replacements"}) {
                    java.lang.reflect.Field f = pawnTable.getClass().getDeclaredField(field);
                    f.setAccessible(true); System.out.println("PAWN " + field + "=" + f.getLong(pawnTable));
                }
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
    }
    private static Object configure(Search search, boolean enabled, boolean stats) {
        try {
            java.lang.reflect.Field f = Search.class.getDeclaredField("pawnTable");
            f.setAccessible(true); Object table = f.get(search);
            java.lang.reflect.Field switchField = table.getClass().getDeclaredField("enabled");
            switchField.setAccessible(true); switchField.setBoolean(table, enabled);
            switchField = table.getClass().getDeclaredField("instrumentationEnabled");
            switchField.setAccessible(true); switchField.setBoolean(table, stats);
            return table;
        } catch (NoSuchFieldException e) {
            // A pre-cache baseline has no cache controls.
            return null;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}

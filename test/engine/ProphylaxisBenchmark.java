package engine;

/** Fresh-process comparison harness for the opt-in prophylaxis prototype. */
public final class ProphylaxisBenchmark {
    public static void main(String[] args) throws Exception {
        int depth = Integer.parseInt(args[0]);
        boolean enabled = Boolean.parseBoolean(args[1]);
        Board board = new Board();
        long[] historyKeys = null;
        if (args.length > 3 && args[2].equals("--history")) {
            for (String row : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(
                    "test", "resources", "search-history-regressions.tsv"))) {
                if (!row.startsWith(args[3] + "\t")) continue;
                String[] fields = row.split("\t", -1);
                board.setFromFen(fields[2]);
                String[] moves = fields[4].split(" ");
                historyKeys = new long[moves.length + 1];
                historyKeys[0] = board.zobristKey;
                for (int i = 0; i < moves.length; i++) {
                    MoveList legal = new MoveList();
                    MoveGenerator.generateLegal(board, legal);
                    int found = Move.NONE;
                    for (int j = 0; j < legal.size; j++)
                        if (Move.toUci(legal.get(j)).equals(moves[i])) found = legal.get(j);
                    if (found == Move.NONE) throw new AssertionError("illegal history move");
                    board.makeMove(found);
                    historyKeys[i + 1] = board.zobristKey;
                }
                if (!board.toFen().equals(fields[3])) throw new AssertionError("history FEN");
                break;
            }
            if (historyKeys == null) throw new AssertionError("unknown history row");
        } else if (args.length > 3 && args[2].equals("--moves")) {
            String[] moves = args[3].split(" ");
            historyKeys = new long[moves.length + 1];
            historyKeys[0] = board.zobristKey;
            for (int i = 0; i < moves.length; i++) {
                MoveList legal = new MoveList();
                MoveGenerator.generateLegal(board, legal);
                int found = Move.NONE;
                for (int j = 0; j < legal.size; j++)
                    if (Move.toUci(legal.get(j)).equals(moves[i])) found = legal.get(j);
                if (found == Move.NONE) throw new AssertionError("illegal history move " + moves[i]);
                board.makeMove(found);
                historyKeys[i + 1] = board.zobristKey;
            }
            System.out.println("historyFen=" + board.toFen());
        } else if (args.length > 2) board.setFromFen(args[2]);
        Search search = new Search(new TranspositionTable(16));
        search.setProphylaxisProbeEnabled(enabled);
        search.setInfoListener((d, sd, score, mate, mateIn, nodes, nps, ms, pv) -> {
            if (d == depth) System.out.println("enabled=" + enabled + " depth=" + d
                    + " nodes=" + nodes + " ms=" + ms + " nps=" + nps
                    + " score=" + score + " pv=" + pv);
        });
        String before = board.toFen();
        long key = board.zobristKey;
        int best = search.search(board, depth, -1, historyKeys);
        if (!before.equals(board.toFen()) || key != board.zobristKey)
            throw new AssertionError("root board state changed");
        System.out.println("best=" + Move.toUci(best));
        Search.ProphylaxisDiagnostics diagnostics = search.getLastProphylaxisDiagnostics();
        System.out.println(diagnostics == null ? "probe=none" : diagnostics.toSummary());
    }
}

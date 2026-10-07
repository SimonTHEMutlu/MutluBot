package engine;

import java.util.LinkedHashMap;
import java.util.Map;

/** Compares normal and LMR-disabled search on the pending Game 2 recapture history. */
public final class SearchHistoryDiagnosticTest {
    private static final String START_FEN = "rnbqkbnr/ppp1pppp/8/3p4/2PP4/8/PP2PPPP/RNBQKBNR b KQkq - 0 2";
    private static final String HISTORY = "e7e6 g1f3 c7c5 c4d5 e6d5 g2g3 g8f6 f1g2 f8e7 b1c3 b8c6 e1g1 e8g8 d4c5 e7c5 c3a4 c5e7 c1e3 c8g4 h2h3 g4f5 a1c1 f6e4 f3d4 c6d4 e3d4 d8a5 e2e3 f8d8 a2a3 a8b8 a4c3 a7a6 c3e2 h7h6 e2f4 d8d7 d1h5 e7g5 g2e4";
    private static final String POSITION_FEN = "1r4k1/1p1r1pp1/p6p/q2p1bbQ/3BBN2/P3P1PP/1P3P2/2R2RK1 b - - 0 22";

    public static void main(String[] args) {
        int depth = args.length == 0 ? 12 : Integer.parseInt(args[0]);
        String selected = args.length < 2 ? "all" : args[1];
        Position position = replay();
        if (selected.equals("all") || selected.equals("normal")) {
            run("normal", position, depth, true);
        }
        if (selected.equals("all") || selected.equals("LMR-disabled")) {
            run("LMR-disabled", position, depth, false);
        }
    }

    private static void run(String name, Position position, int depth, boolean lmrEnabled) {
        Search search = new Search(new TranspositionTable(64));
        search.setInstrumentationEnabled(true);
        search.setLmrEnabledForTesting(lmrEnabled);
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        search.setRootCandidateListener((candidateDepth, rank, move, score, exact, nodes, pv) -> {
            if (candidateDepth == depth) {
                candidates.put(move, new Candidate(rank, score, exact, nodes, pv));
            }
        });
        final String[] finalInfo = {""};
        search.setInfoListener((d, selDepth, score, mate, mateIn, nodes, nps, timeMs, pv) -> {
            if (d == depth) finalInfo[0] = "score=" + score + " pv=" + pv;
        });
        Board board = new Board();
        board.setFromFen(POSITION_FEN);
        int bestMove = search.search(board, depth, -1, position.keys);
        Search.SearchStats stats = search.getLastStats();
        System.out.println(name + " depth=" + depth + " best=" + Move.toUci(bestMove)
                + " " + finalInfo[0] + " nodes=" + stats.totalNodes
                + " qNodes=" + stats.quiescenceNodes + " " + stats.toSummary());
        for (Map.Entry<String, Candidate> entry : candidates.entrySet()) {
            Candidate c = entry.getValue();
            System.out.println("  rank=" + c.rank + " move=" + entry.getKey()
                    + " score=" + c.score + (c.exact ? " exact" : " bound")
                    + " nodes=" + c.nodes + " pv=" + c.pv);
        }
    }

    private static Position replay() {
        Board board = new Board();
        board.setFromFen(START_FEN);
        String[] moves = HISTORY.split(" ");
        long[] keys = new long[moves.length + 1];
        keys[0] = board.zobristKey;
        for (int i = 0; i < moves.length; i++) {
            board.makeMove(findLegalMove(board, moves[i]));
            keys[i + 1] = board.zobristKey;
        }
        check(POSITION_FEN.equals(board.toFen()), "history must reach the exact regression FEN");
        return new Position(keys);
    }

    private static int findLegalMove(Board board, String uci) {
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        for (int i = 0; i < legal.size; i++) {
            int move = legal.get(i);
            if (Move.toUci(move).equals(uci)) return move;
        }
        throw new AssertionError("illegal history move " + uci + " in " + board.toFen());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Position {
        final long[] keys;
        Position(long[] keys) { this.keys = keys; }
    }

    private static final class Candidate {
        final int rank;
        final int score;
        final boolean exact;
        final long nodes;
        final String pv;
        Candidate(int rank, int score, boolean exact, long nodes, String pv) {
            this.rank = rank;
            this.score = score;
            this.exact = exact;
            this.nodes = nodes;
            this.pv = pv;
        }
    }
}

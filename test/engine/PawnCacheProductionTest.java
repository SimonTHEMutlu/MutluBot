package engine;

import java.lang.reflect.Field;
import java.util.Random;

/** Exact cache/reference parity, dependency boundaries and production ownership. */
public final class PawnCacheProductionTest {
    private static int checked;
    public static void main(String[] args) throws Exception {
        Evaluator.PawnTable table = new Evaluator.PawnTable(1);
        table.instrumentationEnabled = true;
        Board board = new Board();
        parity(board, table); parity(board, table);
        require(table.probes == 2 && table.hits == 1 && table.misses == 1
                && table.replacements == 0, "cold miss then warm hit");
        MoveList moves = new MoveList(); MoveGenerator.generateLegal(board, moves);
        board.makeMove(find(moves, "e2e4")); parity(board, table);
        board.unmakeMove(); parity(board, table);
        require(table.replacements == 2, "single-entry collisions replace and reconstruct exactly");
        table.clear();
        require(table.probes == 0 && table.hits == 0 && table.misses == 0
                && table.replacements == 0, "clear resets counters");

        // Each group has identical pawn locations but changes a dynamic dependency.
        String[][] groups = {
            {"4k3/8/4p3/8/2NP4/2P5/8/6K1 w - - 0 1",
             "4k3/8/4p3/8/3P4/2P5/1N6/6K1 b - - 1 1"},
            {"4k3/8/8/3P4/8/8/8/4K3 w - - 0 1",
             "8/4k3/8/3P4/8/8/4K3/8 w - - 0 1"},
            {"3q2k1/ppp2ppp/8/8/8/8/PPP2PPP/3Q2K1 w - - 0 1",
             "6k1/ppp2ppp/8/8/8/8/PPP2PPP/6K1 w - - 0 1"},
            {"4k3/pp6/8/8/8/8/PP6/4K2R w - - 0 1",
             "4k3/pp6/8/8/8/8/PP6/R3K3 w - - 0 1"}
        };
        table = new Evaluator.PawnTable(); table.instrumentationEnabled = true;
        for (String[] group : groups) {
            table.clear(); long key = 0;
            for (int i = 0; i < group.length; i++) {
                board.setFromFen(group[i]);
                if (i == 0) key = board.pawnZobristKey;
                else require(board.pawnZobristKey == key, "dynamic fixture keeps pawn key");
                parity(board, table);
            }
            require(table.hits == 1, "dynamic dependency must still reuse pawn-only entry");
        }
        Random random = new Random(731983L);
        for (String fen : EvaluationParityCorpus.FENS) {
            for (int game = 0; game < 8; game++) {
                board.setFromFen(fen); parity(board, table);
                int plies = 0;
                for (; plies < 100; plies++) {
                    moves.clear(); MoveGenerator.generateLegal(board, moves);
                    if (moves.size == 0) break;
                    board.makeMove(moves.get(random.nextInt(moves.size))); parity(board, table);
                    board.makeNullMove(); parity(board, table); board.unmakeNullMove(); parity(board, table);
                }
                while (plies-- > 0) { board.unmakeMove(); parity(board, table); }
            }
        }
        table.enabled = false;
        long probes = table.probes; parity(board, table);
        require(table.probes == probes, "disabled table bypasses probes");
        Field owned = Search.class.getDeclaredField("pawnTable"); owned.setAccessible(true);
        Search search = new Search(new TranspositionTable(1));
        Evaluator.PawnTable production = (Evaluator.PawnTable) owned.get(search);
        require(production != owned.get(new Search(new TranspositionTable(1))), "Search workers own separate tables");
        production.instrumentationEnabled = true;
        search.setProphylaxisProbeEnabled(false); board.setStartPosition();
        search.search(board, 4, -1, null);
        require(production.probes > 0 && production.hits > 0, "real production search consumes pawn table");
        require(production.probes == production.hits + production.misses, "probe counters partition");
        System.out.println("PawnCacheProductionTest passed checks=" + checked
                + " productionProbes=" + production.probes + " hits=" + production.hits
                + " misses=" + production.misses + " replacements=" + production.replacements);
    }
    private static void parity(Board board, Evaluator.PawnTable table) {
        String before = board.toFen(); long key = board.zobristKey, pawnKey = board.pawnZobristKey;
        int expected = Evaluator.evaluate(board), actual = Evaluator.evaluate(board, table);
        require(actual == expected, "cached evaluation differs: " + before + " expected=" + expected + " actual=" + actual);
        require(before.equals(board.toFen()) && key == board.zobristKey && pawnKey == board.pawnZobristKey,
                "evaluation preserves board"); checked++;
    }
    private static int find(MoveList moves, String uci) {
        for (int i = 0; i < moves.size; i++) if (Move.toUci(moves.get(i)).equals(uci)) return moves.get(i);
        throw new AssertionError("missing " + uci);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

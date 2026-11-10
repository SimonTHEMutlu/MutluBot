package engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Permanent exposed sentinel: hard invariants plus diagnostic move/node reporting. */
public final class SearchRfpActiveKingRegressionTest {
    public static void main(String[] args) throws Exception {
        int depth = 8;
        if (args.length > 0) {
            if (!args[0].equals("--deep")) throw new IllegalArgumentException("Use --deep for depth21");
            depth = 21;
        }
        Path resource = Paths.get("test", "resources", "search-performance-regressions.tsv");
        int count = 0;
        for (String line : Files.readAllLines(resource, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("id\t")) continue;
            String[] f = line.split("\t", -1);
            check(f.length == 10, "performance sentinel requires ten fields");
            check(f[0].equals("rfp_active_king_rook"), "unexpected fixture id");
            Board fixture = new Board();
            fixture.setFromFen(f[1]);
            check(fixture.toFen().equals(f[1]), "FEN round trip");
            check(fixture.sideToMove == Piece.BLACK, "Black must be to move");
            check(!fixture.isInCheck(Piece.WHITE) && !fixture.isInCheck(Piece.BLACK),
                    "fixture must be legal and quiet");
            findLegal(fixture, f[3]);
            int rookCheck = findLegal(fixture, f[4]);
            String before = snapshot(fixture);
            fixture.makeMove(rookCheck);
            check(fixture.isInCheck(Piece.WHITE), "historical Rf7 move must give check");
            fixture.unmakeMove();
            check(before.equals(snapshot(fixture)), "fixture make/unmake restoration");
            check(Integer.parseInt(f[2]) == 21 && Long.parseLong(f[8]) > Long.parseLong(f[7]),
                    "preserve the historical depth and node-regression evidence");

            Result off = null, on = null;
            for (boolean rfp : new boolean[] {false, true}) {
                Result normal = run(f[1], depth, rfp, false);
                Result counted = run(f[1], depth, rfp, true);
                check(normal.signature.equals(counted.signature),
                        "instrumentation changes nodes/move/score/PV/depth/seldepth, RFP=" + rfp);
                check(normal.nodes == counted.nodes, "instrumentation changes total nodes");
                check(counted.stats.evaluatorCalls > 0, "evaluation counters must be active");
                check(normal.stats.evaluatorCalls == 0 && normal.stats.rfpCutoffs == 0,
                        "normal search must leave detailed counters off");
                if (!rfp) check(counted.stats.rfpEligibilityChecks == 0,
                        "disabled RFP must never enter the RFP decision");
                else check(counted.stats.rfpEligibilityChecks > 0,
                        "enabled RFP must be exercised by the fixture");
                System.out.println("BEHAVIORAL_PERFORMANCE_SENTINEL " + f[0]
                        + " rfp=" + rfp + " " + normal.signature
                        + " timeMs=" + normal.timeMs + " evalCalls=" + counted.stats.evaluatorCalls
                        + " rfpCutoffs=" + counted.stats.rfpCutoffs
                        + " historicalMove=" + (rfp ? f[4] : f[3]));
                if (rfp) on = normal; else off = normal;
            }
            System.out.println("DIAGNOSTIC candidate/baseline nodes="
                    + ((double) on.nodes / off.nodes)
                    + " (no required move, score, or speed threshold)");
            count++;
        }
        check(count == 1, "permanent active-king fixture missing/duplicated");
        System.out.println("SearchRfpActiveKingRegressionTest passed");
    }

    private static Result run(String fen, int depth, boolean rfp, boolean instrumented) {
        Board board = new Board();
        board.setFromFen(fen);
        String before = snapshot(board);
        Search search = new Search(new TranspositionTable(256));
        search.setProphylaxisProbeEnabled(false);
        search.setReverseFutilityEnabled(rfp);
        search.setInstrumentationEnabled(instrumented);
        final String[] info = {null};
        final long[] time = {0};
        search.setInfoListener((d, sd, cp, mate, mateIn, nodes, nps, ms, pv) -> {
            info[0] = "depth=" + d + " seldepth=" + sd + " score=" + cp
                    + " mate=" + mate + " mateIn=" + mateIn + " nodes=" + nodes + " pv=" + pv;
            time[0] = ms;
            Board replay = new Board();
            replay.setFromFen(fen);
            if (!pv.trim().isEmpty()) for (String uci : pv.trim().split(" +"))
                replay.makeMove(findLegal(replay, uci));
        });
        int best = search.search(board, depth, -1, null);
        check(info[0] != null && info[0].startsWith("depth=" + depth + " "),
                "must finish requested depth");
        check(best != Move.NONE && findLegal(board, Move.toUci(best)) == best,
                "search must return a legal move");
        check(before.equals(snapshot(board)), "search must restore all board/evaluation state");
        Search.SearchStats stats = search.getLastStats();
        return new Result(info[0] + " bestmove=" + Move.toUci(best), stats.totalNodes,
                time[0], stats);
    }

    private static int findLegal(Board board, String uci) {
        MoveList moves = new MoveList();
        MoveGenerator.generateLegal(board, moves);
        for (int i = 0; i < moves.size; i++)
            if (Move.toUci(moves.get(i)).equals(uci)) return moves.get(i);
        throw new AssertionError("Illegal move " + uci + " in " + board.toFen());
    }
    private static String snapshot(Board b) {
        return b.toFen() + "|" + b.zobristKey + "|" + b.pawnZobristKey
                + "|" + Arrays.deepToString(b.pieceBB) + "|" + Arrays.toString(b.occupancy)
                + "|" + b.allOccupancy + "|" + Arrays.toString(b.mailbox)
                + "|" + b.incrementalMaterialScore + "|" + b.incrementalMgPstScore
                + "|" + b.incrementalEgPstScore + "|" + b.phaseUnits;
    }
    private static final class Result {
        final String signature;
        final long nodes, timeMs;
        final Search.SearchStats stats;
        Result(String signature, long nodes, long timeMs, Search.SearchStats stats) {
            this.signature = signature; this.nodes = nodes; this.timeMs = timeMs; this.stats = stats;
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

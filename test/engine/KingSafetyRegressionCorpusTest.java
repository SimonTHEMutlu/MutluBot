package engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Validates the structural king-safety regression corpus.
 *
 * <p>Rows begin as {@code pending}: they prove that the supplied position and
 * candidate moves are well-formed but do not claim current engine behavior.
 * Invoke with {@code --enforce-pending} to additionally run every row at the
 * documented fixed depth and require a target move while rejecting forbidden
 * moves. This is deliberately opt-in until a case has stable match evidence.</p>
 */
public final class KingSafetyRegressionCorpusTest {
    private static final int FIELD_COUNT = 8;
    private static final int ENFORCEMENT_DEPTH = 12;

    public static void main(String[] args) throws Exception {
        boolean enforcePending = false;
        Path corpus = Paths.get("test", "resources", "king-safety-regressions.tsv");
        for (String arg : args) {
            if ("--enforce-pending".equals(arg)) enforcePending = true;
            else corpus = Paths.get(arg);
        }
        Counts counts = validate(corpus, enforcePending);
        System.out.println("KingSafetyRegressionCorpusTest: " + counts.total
                + " rows validated (active=" + counts.active + ", pending="
                + counts.pending + ", enforced=" + counts.enforced + ")");
    }

    private static Counts validate(Path corpus, boolean enforcePending) throws IOException {
        Counts counts = new Counts();
        boolean sawHeader = false;
        Set<String> ids = new HashSet<>();

        try (BufferedReader reader = Files.newBufferedReader(corpus, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!sawHeader) {
                    check(line.equals("id\tdescription\tfen\ttarget_moves\tforbidden_moves\tstatus\tsource\tnotes"),
                            "unexpected corpus header at line " + lineNumber);
                    sawHeader = true;
                    continue;
                }
                String[] fields = line.split("\t", -1);
                check(fields.length == FIELD_COUNT, "line " + lineNumber + " has "
                        + fields.length + " fields; expected " + FIELD_COUNT);
                validateRow(fields, lineNumber, ids, enforcePending, counts);
            }
        }
        check(sawHeader, "corpus header is missing");
        check(counts.total > 0, "corpus must contain at least one row");
        return counts;
    }

    private static void validateRow(String[] f, int lineNumber, Set<String> ids,
                                    boolean enforcePending, Counts counts) {
        String context = "line " + lineNumber + " (" + f[0] + ")";
        check(!f[0].isEmpty() && ids.add(f[0]), context + ": duplicate or empty id");
        check(!f[1].isEmpty() && !f[6].isEmpty(), context + ": description and source are required");
        check(f[5].equals("pending") || f[5].equals("active"),
                context + ": status must be pending or active");

        Board board = new Board();
        board.setFromFen(f[2]);
        check(f[2].equals(board.toFen()), context + ": FEN did not round-trip");
        check(!board.isInCheck(Piece.opposite(board.sideToMove)),
                context + ": side that just moved is left in check");

        Set<String> targets = moves(f[3]);
        Set<String> forbidden = moves(f[4]);
        check(!targets.isEmpty(), context + ": target move set is empty");
        for (String move : targets) {
            check(!forbidden.contains(move), context + ": move is both target and forbidden: " + move);
            findLegalMove(board, move, context);
        }
        for (String move : forbidden) findLegalMove(board, move, context);

        counts.total++;
        if (f[5].equals("pending")) counts.pending++; else counts.active++;
        if (f[5].equals("active") || enforcePending) {
            Search search = new Search(new TranspositionTable(16));
            int bestMove = search.search(board, ENFORCEMENT_DEPTH, -1, null);
            String actual = Move.toUci(bestMove);
            check(targets.contains(actual), context + ": depth " + ENFORCEMENT_DEPTH
                    + " selected " + actual + ", expected one of " + targets);
            check(!forbidden.contains(actual), context + ": depth " + ENFORCEMENT_DEPTH
                    + " selected forbidden move " + actual);
            counts.enforced++;
        }
    }

    private static Set<String> moves(String value) {
        Set<String> result = new HashSet<>();
        if (value.isEmpty() || value.equals("-")) return result;
        result.addAll(Arrays.asList(value.split(",")));
        return result;
    }

    private static int findLegalMove(Board board, String uci, String context) {
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        for (int i = 0; i < legal.size; i++) {
            int move = legal.get(i);
            if (Move.toUci(move).equals(uci)) return move;
        }
        throw new AssertionError(context + ": illegal move " + uci + " in " + board.toFen());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Counts {
        int total;
        int active;
        int pending;
        int enforced;
    }
}

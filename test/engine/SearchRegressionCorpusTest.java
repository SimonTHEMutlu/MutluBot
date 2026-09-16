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

/** Validates the machine-readable positive search regression corpus. */
public final class SearchRegressionCorpusTest {
    private static final int FIELD_COUNT = 18;

    public static void main(String[] args) throws Exception {
        Path corpus = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("test", "resources", "search-regressions.tsv");
        int count = validate(corpus);
        check(count > 0, "corpus must contain at least one regression");
        System.out.println("SearchRegressionCorpusTest: " + count + " positions passed");
    }

    private static int validate(Path corpus) throws IOException {
        int count = 0;
        boolean sawHeader = false;
        Set<String> ids = new HashSet<>();

        try (BufferedReader reader = Files.newBufferedReader(corpus, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!sawHeader) {
                    check(line.startsWith("id\tdescription\tfen\t"),
                            "unexpected corpus header at line " + lineNumber);
                    sawHeader = true;
                    continue;
                }

                String[] fields = line.split("\t", -1);
                check(fields.length == FIELD_COUNT,
                        "line " + lineNumber + " has " + fields.length
                                + " fields; expected " + FIELD_COUNT);
                validateRow(fields, lineNumber, ids);
                count++;
            }
        }

        check(sawHeader, "corpus header is missing");
        return count;
    }

    private static void validateRow(String[] f, int lineNumber, Set<String> ids) {
        String context = "line " + lineNumber + " (" + f[0] + ")";
        check(!f[0].isEmpty() && ids.add(f[0]), context + ": duplicate or empty id");

        Board board = new Board();
        board.setFromFen(f[2]);
        check(f[2].equals(board.toFen()), context + ": FEN did not round-trip");
        check(!board.isInCheck(Piece.opposite(board.sideToMove)),
                context + ": side that just moved is left in check");

        Set<String> accepted = moves(f[4]);
        Set<String> forbidden = moves(f[5]);
        check(!accepted.isEmpty(), context + ": accepted move set is empty");
        check(accepted.contains(f[3]), context + ": played move is not accepted");
        for (String move : accepted) {
            check(!forbidden.contains(move), context + ": move is both accepted and forbidden: " + move);
            findLegalMove(board, move, context);
        }
        for (String move : forbidden) findLegalMove(board, move, context);

        int fixedDepth = positiveInt(f[6], context + ": fixed depth");
        Integer.parseInt(f[7]); // A score may legitimately be negative or zero.
        positiveLong(f[8], context + ": fixed nodes");
        nonNegativeLong(f[9], context + ": fixed time");

        int firstDepth = positiveInt(f[10], context + ": first depth");
        long firstTime = nonNegativeLong(f[11], context + ": first time");
        long firstNodes = positiveLong(f[12], context + ": first nodes");
        int stableDepth = positiveInt(f[13], context + ": stable depth");
        long stableTime = nonNegativeLong(f[14], context + ": stable time");
        long stableNodes = positiveLong(f[15], context + ": stable nodes");
        check(firstDepth <= stableDepth && stableDepth <= fixedDepth,
                context + ": expected first depth <= stable depth <= fixed depth");
        check(firstTime <= stableTime, context + ": stable time precedes first-seen time");
        check(firstNodes <= stableNodes, context + ": stable nodes precede first-seen nodes");

        String[] pv = f[16].split(" ");
        check(pv.length > 0 && accepted.contains(pv[0]),
                context + ": fixed-depth PV must begin with an accepted move");
        Board pvBoard = new Board();
        pvBoard.setFromFen(f[2]);
        for (String move : pv) {
            int encoded = findLegalMove(pvBoard, move, context + " PV " + Arrays.toString(pv));
            pvBoard.makeMove(encoded);
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

    private static int positiveInt(String value, String context) {
        int parsed = Integer.parseInt(value);
        check(parsed > 0, context + " must be positive");
        return parsed;
    }

    private static long positiveLong(String value, String context) {
        long parsed = Long.parseLong(value);
        check(parsed > 0, context + " must be positive");
        return parsed;
    }

    private static long nonNegativeLong(String value, String context) {
        long parsed = Long.parseLong(value);
        check(parsed >= 0, context + " must be non-negative");
        return parsed;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

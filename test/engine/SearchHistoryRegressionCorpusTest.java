package engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates search regressions whose behavior depends on the complete game history. */
public final class SearchHistoryRegressionCorpusTest {
    private static final int FIELD_COUNT = 10;
    private static final int ENFORCEMENT_DEPTH = 12;

    public static void main(String[] args) throws Exception {
        boolean enforcePending = false;
        Path corpus = Paths.get("test", "resources", "search-history-regressions.tsv");
        for (String arg : args) {
            if ("--enforce-pending".equals(arg)) enforcePending = true;
            else corpus = Paths.get(arg);
        }
        int count = validate(corpus, enforcePending);
        check(count > 0, "corpus must contain at least one regression");
        System.out.println("SearchHistoryRegressionCorpusTest: " + count + " rows validated");
    }

    private static int validate(Path corpus, boolean enforcePending) throws IOException {
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
                    check(line.equals("id\tdescription\tstart_fen\texpected_fen\thistory\ttarget_moves\tforbidden_moves\tstatus\tsource\tnotes"),
                            "unexpected corpus header at line " + lineNumber);
                    sawHeader = true;
                    continue;
                }
                String[] fields = line.split("\t", -1);
                check(fields.length == FIELD_COUNT, "line " + lineNumber + " has "
                        + fields.length + " fields; expected " + FIELD_COUNT);
                validateRow(fields, lineNumber, ids, enforcePending);
                count++;
            }
        }
        check(sawHeader, "corpus header is missing");
        return count;
    }

    private static void validateRow(String[] f, int lineNumber, Set<String> ids,
                                    boolean enforcePending) {
        String context = "line " + lineNumber + " (" + f[0] + ")";
        check(!f[0].isEmpty() && ids.add(f[0]), context + ": duplicate or empty id");
        check(!f[1].isEmpty() && !f[8].isEmpty(), context + ": description and source are required");
        check(f[7].equals("pending") || f[7].equals("active"), context + ": invalid status");

        Board board = new Board();
        board.setFromFen(f[2]);
        check(f[2].equals(board.toFen()), context + ": starting FEN did not round-trip");
        List<Long> historyKeys = new ArrayList<>();
        historyKeys.add(board.zobristKey);
        for (String uci : f[4].split(" ")) {
            int move = findLegalMove(board, uci, context);
            board.makeMove(move);
            historyKeys.add(board.zobristKey);
        }

        check(f[3].equals(board.toFen()), context + ": history does not reach its documented FEN");
        Set<String> targets = moves(f[5]);
        Set<String> forbidden = moves(f[6]);
        check(!targets.isEmpty(), context + ": target move set is empty");
        for (String move : targets) {
            check(!forbidden.contains(move), context + ": move is both target and forbidden: " + move);
            findLegalMove(board, move, context);
        }
        for (String move : forbidden) findLegalMove(board, move, context);
        if (f[7].equals("active") || enforcePending) {
            long[] keys = new long[historyKeys.size()];
            for (int i = 0; i < keys.length; i++) keys[i] = historyKeys.get(i);
            Search search = new Search(new TranspositionTable(16));
            int bestMove = search.search(board, ENFORCEMENT_DEPTH, -1, keys);
            check(targets.contains(Move.toUci(bestMove)), context + ": depth "
                    + ENFORCEMENT_DEPTH + " selected " + Move.toUci(bestMove)
                    + ", expected one of " + targets);
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
}

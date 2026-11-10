package engine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** UCI option lifetime, TT invalidation, and search/ponder transition checks. */
public final class UciRfpProtocolTest {
    private static final Method COMMAND;
    static {
        try {
            COMMAND = UCIEngine.class.getDeclaredMethod("handleCommand", String.class);
            COMMAND.setAccessible(true);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    public static void main(String[] args) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        UCIEngine engine = new UCIEngine();
        try (PrintStream capture = new PrintStream(bytes, true, "UTF-8")) {
            System.setOut(capture);
            command(engine, "uci");
            check(bytes.toString("UTF-8").contains(
                    "option name ReverseFutilityPruning type check default true"),
                    "UCI must advertise enabled-by-default RFP");
            check(search(engine).isReverseFutilityEnabled(), "default must be enabled");

            TranspositionTable tt = (TranspositionTable) field(engine, "tt");
            long key = new Board().zobristKey;
            tt.store(key, 6, 321, TranspositionTable.EXACT, Move.NONE);
            command(engine, "setoption name ReverseFutilityPruning value false");
            check(!search(engine).isReverseFutilityEnabled(), "false must disable RFP");
            check(tt.probePacked(key) == 0, "policy toggle must invalidate old TT bounds");
            command(engine, "ucinewgame");
            check(!search(engine).isReverseFutilityEnabled(), "new game must preserve false");
            Search oldSearch = search(engine);
            command(engine, "setoption name Hash value 16");
            check(search(engine) != oldSearch, "Hash must recreate Search");
            check(!search(engine).isReverseFutilityEnabled(), "Hash must preserve false");
            check(!((Boolean) field(engine, "reverseFutilityEnabled")),
                    "UCI's stored option must also remain false");
            runLegalSearch(engine, bytes);

            command(engine, "setoption name reversefutilitypruning value TRUE");
            check(search(engine).isReverseFutilityEnabled(), "option must be case insensitive");
            command(engine, "setoption name Hash value 16");
            command(engine, "ucinewgame");
            check(search(engine).isReverseFutilityEnabled(), "Hash/new game must preserve true");
            runLegalSearch(engine, bytes);

            command(engine, "go ponder depth 1");
            Thread worker = (Thread) field(engine, "searchThread");
            check(worker != null, "ponder must create a worker");
            command(engine, "setoption name ReverseFutilityPruning value false");
            check(!worker.isAlive(), "policy change must stop/join even a gated ponder");
            check(!search(engine).isReverseFutilityEnabled(), "ponder transition must apply false");
            runLegalSearch(engine, bytes);
        } finally {
            command(engine, "quit");
            System.setOut(original);
        }
        System.out.println("UciRfpProtocolTest passed");
    }

    private static void runLegalSearch(UCIEngine engine, ByteArrayOutputStream bytes)
            throws Exception {
        command(engine, "position startpos moves e2e4 e7e5 g1f3 b8c6");
        bytes.reset();
        command(engine, "go depth 4");
        Thread worker = (Thread) field(engine, "searchThread");
        worker.join(10000);
        check(!worker.isAlive(), "bounded UCI search must complete");
        String best = null;
        for (String line : bytes.toString("UTF-8").split("\\R")) {
            if (line.startsWith("bestmove ")) {
                check(best == null, "exactly one bestmove is required");
                best = line.split(" ")[1];
            }
        }
        check(best != null, "search must emit bestmove");
        Board board = (Board) field(engine, "board");
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        boolean found = false;
        for (int i = 0; i < legal.size; i++) found |= Move.toUci(legal.get(i)).equals(best);
        check(found, "UCI bestmove must be legal: " + best);
    }

    private static void command(UCIEngine engine, String command) throws Exception {
        COMMAND.invoke(engine, command);
    }
    private static Search search(UCIEngine engine) throws Exception {
        return (Search) field(engine, "search");
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

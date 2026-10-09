package engine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Process-level regressions for the UCI pondering lifecycle. */
public final class UciPonderProtocolTest {

    public static void main(String[] args) throws Exception {
        testPonderAdvertisementAndPrediction();
        testPonderWaitsForHit();
        testStopReleasesCompletedPonder();
        System.out.println("UciPonderProtocolTest OK");
    }

    private static void testPonderAdvertisementAndPrediction() throws Exception {
        try (EngineSession engine = new EngineSession()) {
            engine.send("uci");
            boolean advertised = false;
            String line;
            while (!(line = engine.next(2000)).equals("uciok")) {
                if (line.equals("option name Ponder type check default false")) advertised = true;
            }
            check(advertised, "engine must advertise the UCI Ponder option");

            engine.send("setoption name Ponder value true");
            engine.send("position startpos moves e2e4 e7e5");
            engine.send("go depth 2");
            String bestmove = engine.waitForPrefix("bestmove ", 3000);
            check(bestmove.contains(" ponder "),
                    "normal search must provide a predicted reply so the GUI can start pondering");
        }
    }

    private static void testPonderWaitsForHit() throws Exception {
        try (EngineSession engine = new EngineSession()) {
            engine.initialize();
            engine.send("position startpos moves e2e4 e7e5 g1f3 b8c6");
            engine.send("go ponder movetime 120");
            check(!engine.seesPrefix("bestmove ", 300),
                    "ponder search must not emit bestmove before ponderhit");
            engine.send("ponderhit");
            String bestmove = engine.waitForPrefix("bestmove ", 3000);
            check(bestmove.matches("bestmove [a-h][1-8][a-h][1-8][qrbn]?( ponder .+)?"),
                    "ponderhit must eventually produce a legal-looking bestmove");
        }
    }

    private static void testStopReleasesCompletedPonder() throws Exception {
        try (EngineSession engine = new EngineSession()) {
            engine.initialize();
            engine.send("position startpos");
            engine.send("go ponder depth 1");
            check(!engine.seesPrefix("bestmove ", 150),
                    "a completed bounded ponder must remain gated");
            engine.send("stop");
            engine.waitForPrefix("bestmove ", 2000);
            check(!engine.seesPrefix("bestmove ", 150),
                    "stop must release exactly one bestmove");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class EngineSession implements AutoCloseable {
        private final Process process;
        private final BufferedWriter input;
        private final LinkedBlockingQueue<String> output = new LinkedBlockingQueue<>();

        EngineSession() throws Exception {
            String javaCommand = System.getProperty("java.home") + java.io.File.separator
                    + "bin" + java.io.File.separator + "java";
            process = new ProcessBuilder(javaCommand, "-cp", System.getProperty("java.class.path"),
                    "engine.UCIEngine").redirectErrorStream(true).start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), "UTF-8"));
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"));
            Thread pump = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) output.offer(line);
                } catch (Exception ignored) {
                    // The process shutdown closes the stream.
                }
            }, "uci-test-output");
            pump.setDaemon(true);
            pump.start();
        }

        void initialize() throws Exception {
            send("uci");
            waitForPrefix("uciok", 2000);
            send("setoption name Ponder value true");
            send("isready");
            waitForPrefix("readyok", 2000);
        }

        void send(String command) throws Exception {
            input.write(command);
            input.newLine();
            input.flush();
        }

        String next(long timeoutMs) throws Exception {
            String line = output.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (line == null) throw new AssertionError("timed out waiting for engine output");
            return line;
        }

        String waitForPrefix(String prefix, long timeoutMs) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (System.nanoTime() < deadline) {
                long remaining = deadline - System.nanoTime();
                String line = output.poll(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)),
                        TimeUnit.MILLISECONDS);
                if (line != null && line.startsWith(prefix)) return line;
            }
            throw new AssertionError("timed out waiting for output prefix: " + prefix);
        }

        boolean seesPrefix(String prefix, long durationMs) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs);
            while (System.nanoTime() < deadline) {
                long remaining = deadline - System.nanoTime();
                String line = output.poll(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)),
                        TimeUnit.MILLISECONDS);
                if (line != null && line.startsWith(prefix)) return true;
            }
            return false;
        }

        @Override
        public void close() throws Exception {
            if (process.isAlive()) {
                send("quit");
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        }
    }
}

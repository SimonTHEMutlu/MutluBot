package engine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * UCI (Universal Chess Interface) protocol front-end. This is what a GUI
 * such as SCID vs PC, Arena, or CuteChess talks to over stdin/stdout.
 *
 * Supported commands: uci, isready, ucinewgame, position, go, stop,
 * setoption (Hash), quit. Also a couple of small debug extras: "d" prints
 * the board, "perft N" runs a perft test from the current position.
 */
public class UCIEngine {

    private static final String ENGINE_NAME = "MutluBot v3 BETA";
    private static final String ENGINE_AUTHOR = "Basri Mutlu";

    private Board board = new Board();
    private TranspositionTable tt = new TranspositionTable(64); // MB
    private Search search = new Search(tt);
    private final List<Long> gameHistory = new ArrayList<>();

    private Thread searchThread;

    public static void main(String[] args) {
        new UCIEngine().run();
    }

    private void run() {
        gameHistory.add(board.zobristKey);
        search.setInfoListener(this::printInfo);

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        try {
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (!handleCommand(line)) break;
            }
        } catch (Exception e) {
            // Never let an unexpected exception kill the process silently without a trace;
            // print to stderr (not stdout, which is reserved for UCI protocol messages).
            e.printStackTrace();
        }
    }

    /** Returns false to signal the main loop should exit (i.e. "quit"). */
    private boolean handleCommand(String line) {
        String[] tokens = line.split("\\s+");
        switch (tokens[0]) {
            case "uci":
                send("id name " + ENGINE_NAME);
                send("id author " + ENGINE_AUTHOR);
                send("option name Hash type spin default 64 min 1 max 4096");
                send("uciok");
                break;
            case "isready":
                send("readyok");
                break;
            case "ucinewgame":
                stopSearchIfRunning();
                tt.clear();
                board.setStartPosition();
                gameHistory.clear();
                gameHistory.add(board.zobristKey);
                break;
            case "position":
                stopSearchIfRunning();
                handlePosition(tokens);
                break;
            case "go":
                handleGo(tokens);
                break;
            case "stop":
                stopSearchIfRunning();
                break;
            case "setoption":
                handleSetOption(line);
                break;
            case "d":
            case "print":
                board.printBoard();
                System.out.println("FEN: " + board.toFen());
                System.out.println("Zobrist: " + Long.toHexString(board.zobristKey));
                break;
            case "perft":
                if (tokens.length > 1) {
                    int depth = Integer.parseInt(tokens[1]);
                    long start = System.currentTimeMillis();
                    long nodes = Perft.perft(board, depth);
                    long elapsed = System.currentTimeMillis() - start;
                    System.out.println("Perft(" + depth + ") = " + nodes + "  (" + elapsed + " ms)");
                }
                break;
            case "quit":
                stopSearchIfRunning();
                return false;
            default:
                // Unknown command: ignore, per typical UCI engine behavior.
                break;
        }
        return true;
    }

    private void handlePosition(String[] tokens) {
        int idx = 1;
        if (idx >= tokens.length) return;
        if (tokens[idx].equals("startpos")) {
            board.setStartPosition();
            idx++;
        } else if (tokens[idx].equals("fen")) {
            idx++;
            StringBuilder fen = new StringBuilder();
            while (idx < tokens.length && !tokens[idx].equals("moves")) {
                fen.append(tokens[idx]).append(' ');
                idx++;
            }
            board.setFromFen(fen.toString().trim());
        } else {
            return;
        }

        gameHistory.clear();
        gameHistory.add(board.zobristKey);

        if (idx < tokens.length && tokens[idx].equals("moves")) {
            idx++;
            for (; idx < tokens.length; idx++) {
                int move = parseUciMove(board, tokens[idx]);
                if (move == Move.NONE) break;
                board.makeMove(move);
                gameHistory.add(board.zobristKey);
            }
        }
    }

    private int parseUciMove(Board b, String uciStr) {
        if (uciStr.length() < 4) return Move.NONE;
        int from, to;
        try {
            from = Bitboards.squareFromName(uciStr.substring(0, 2));
            to = Bitboards.squareFromName(uciStr.substring(2, 4));
        } catch (Exception e) {
            return Move.NONE;
        }
        Integer promoType = null;
        if (uciStr.length() >= 5) {
            switch (Character.toLowerCase(uciStr.charAt(4))) {
                case 'q': promoType = Piece.QUEEN; break;
                case 'r': promoType = Piece.ROOK; break;
                case 'b': promoType = Piece.BISHOP; break;
                case 'n': promoType = Piece.KNIGHT; break;
            }
        }
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(b, legal);
        for (int i = 0; i < legal.size; i++) {
            int move = legal.get(i);
            if (Move.from(move) == from && Move.to(move) == to) {
                if (Move.isPromotion(move)) {
                    if (promoType != null && Move.promotionPieceType(move) == promoType) return move;
                } else if (promoType == null) {
                    return move;
                }
            }
        }
        return Move.NONE;
    }

    private void handleGo(String[] tokens) {
        stopSearchIfRunning();

        int depth = 0;
        long movetime = -1, wtime = -1, btime = -1, winc = 0, binc = 0;
        int movestogo = 0;
        boolean infinite = false;

        for (int i = 1; i < tokens.length; i++) {
            switch (tokens[i]) {
                case "depth": depth = Integer.parseInt(tokens[++i]); break;
                case "movetime": movetime = Long.parseLong(tokens[++i]); break;
                case "wtime": wtime = Long.parseLong(tokens[++i]); break;
                case "btime": btime = Long.parseLong(tokens[++i]); break;
                case "winc": winc = Long.parseLong(tokens[++i]); break;
                case "binc": binc = Long.parseLong(tokens[++i]); break;
                case "movestogo": movestogo = Integer.parseInt(tokens[++i]); break;
                case "infinite": infinite = true; break;
                // "ponder", "nodes", "mate", "searchmoves" accepted but not specially handled in v1
                default: break;
            }
        }

        long timeBudget;
        int depthLimit = depth;

        if (infinite) {
            timeBudget = -1;
        } else if (movetime >= 0) {
            timeBudget = movetime;
        } else if (wtime >= 0 || btime >= 0) {
            long myTime = board.sideToMove == Piece.WHITE ? wtime : btime;
            long myInc = board.sideToMove == Piece.WHITE ? winc : binc;
            if (myTime < 0) myTime = 5000;
            int mtg = movestogo > 0 ? movestogo : 30;
            long allocated = myTime / mtg + (long) (myInc * 0.8);
            allocated -= 50; // overhead safety margin
            long maxAllowed = myTime - 100;
            if (allocated > maxAllowed) allocated = maxAllowed;
            if (allocated < 20) allocated = 20;
            timeBudget = allocated;
        } else if (depth > 0) {
            timeBudget = -1;
        } else {
            timeBudget = 5000; // sensible default if the GUI gave us nothing to go on
        }

        long[] historyArray = new long[gameHistory.size()];
        for (int i = 0; i < historyArray.length; i++) historyArray[i] = gameHistory.get(i);

        final int fDepthLimit = depthLimit;
        final long fTimeBudget = timeBudget;

        searchThread = new Thread(() -> {
            int best = search.search(board, fDepthLimit, fTimeBudget, historyArray);
            send("bestmove " + Move.toUci(best));
        }, "search-thread");
        searchThread.start();
    }

    private void stopSearchIfRunning() {
        if (searchThread != null && searchThread.isAlive()) {
            search.requestStop();
            try {
                searchThread.join(2000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void handleSetOption(String line) {
        // Format: setoption name <id> [value <x>]
        String lower = line.toLowerCase();
        int nameIdx = lower.indexOf("name");
        int valueIdx = lower.indexOf("value");
        if (nameIdx < 0) return;
        String name = valueIdx > nameIdx
                ? line.substring(nameIdx + 4, valueIdx).trim()
                : line.substring(nameIdx + 4).trim();
        String value = valueIdx >= 0 ? line.substring(valueIdx + 5).trim() : "";

        if (name.equalsIgnoreCase("Hash")) {
            try {
                int mb = Integer.parseInt(value);
                tt = new TranspositionTable(mb);
                search = new Search(tt);
                search.setInfoListener(this::printInfo);
            } catch (NumberFormatException ignored) {
            }
        }
        // Other options (e.g. Threads) can be added here as the engine grows.
    }

    private void printInfo(int depth, int seldepth, int scoreCp, boolean isMate, int mateIn,
                            long nodes, long nps, long timeMs, String pv) {
        StringBuilder sb = new StringBuilder();
        sb.append("info depth ").append(depth)
          .append(" seldepth ").append(seldepth)
          .append(" score ");
        if (isMate) sb.append("mate ").append(mateIn);
        else sb.append("cp ").append(scoreCp);
        sb.append(" nodes ").append(nodes)
          .append(" nps ").append(nps)
          .append(" time ").append(timeMs);
        if (!pv.isEmpty()) sb.append(" pv ").append(pv);
        send(sb.toString());
    }

    private void send(String s) {
        System.out.println(s);
        System.out.flush();
    }
}

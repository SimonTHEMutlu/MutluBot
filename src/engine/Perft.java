package engine;

public class Perft {

    public static long perft(Board b, int depth) {
        if (depth == 0) return 1L;
        MoveList list = new MoveList();
        MoveGenerator.generatePseudoLegal(b, list, false);
        int us = b.sideToMove;
        long nodes = 0L;
        for (int i = 0; i < list.size; i++) {
            int move = list.get(i);
            b.makeMove(move);
            if (!b.isInCheck(us)) {
                nodes += perft(b, depth - 1);
            }
            b.unmakeMove();
        }
        return nodes;
    }

    /** Divide: prints node count per root move, useful for finding bugs. */
    public static void divide(Board b, int depth) {
        MoveList list = new MoveList();
        MoveGenerator.generatePseudoLegal(b, list, false);
        int us = b.sideToMove;
        long total = 0;
        for (int i = 0; i < list.size; i++) {
            int move = list.get(i);
            b.makeMove(move);
            if (!b.isInCheck(us)) {
                long nodes = depth > 1 ? perft(b, depth - 1) : 1L;
                total += nodes;
                System.out.println(Move.toUci(move) + ": " + nodes);
            }
            b.unmakeMove();
        }
        System.out.println("Total: " + total);
    }

    public static void main(String[] args) {
        Board b = new Board();
        int depth = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        String fen = args.length > 1 ? args[1] : null;
        if (fen != null) b.setFromFen(fen);
        long start = System.currentTimeMillis();
        long nodes = perft(b, depth);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("Perft(" + depth + ") = " + nodes + "  (" + elapsed + " ms, "
                + (elapsed > 0 ? (nodes * 1000L / elapsed) : nodes) + " nps)");
    }
}

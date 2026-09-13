package engine;

public final class StaticExchangeTest {
    private static void expect(String fen, String uci, int expected) {
        Board board = new Board();
        board.setFromFen(fen);
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        for (int i = 0; i < legal.size; i++) {
            int move = legal.get(i);
            if (Move.toUci(move).equals(uci)) {
                int actual = StaticExchange.evaluate(board, move, new int[32]);
                if (actual != expected) {
                    throw new AssertionError(uci + ": expected " + expected + ", got " + actual);
                }
                return;
            }
        }
        throw new AssertionError("No legal move " + uci + " in " + fen);
    }

    public static void main(String[] args) {
        expect("4k3/8/8/8/4q3/8/4R3/4K3 w - - 0 1", "e2e4", 900);
        expect("4k3/8/8/4r3/4p3/8/4Q3/4K3 w - - 0 1", "e2e4", -800);
        expect("2k5/8/8/2n5/4p3/8/4R3/2R1K3 w - - 0 1", "e2e4", 100);
        expect("8/8/8/4k3/R3p3/8/4R3/4K3 w - - 0 1", "e2e4", 100);
        expect("4k3/8/8/4r3/4p3/8/4R3/K3R3 w - - 0 1", "e2e4", 100);
        expect("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", "e5d6", 100);
        expect("k6r/6P1/8/8/8/8/8/4K3 w - - 0 1", "g7h8q", 1300);
        System.out.println("StaticExchangeTest passed");
    }
}

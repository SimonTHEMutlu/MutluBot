package engine;

import java.util.Random;

/** Differential existence checks over reproducible legal game histories. */
public final class LegalMoveExistenceTest {
    public static void main(String[] args) {
        for (String fen : new String[] {
                "7k/8/8/8/8/8/8/r3K3 w - - 0 1",
                "7k/8/8/8/8/8/4r3/4K3 w - - 0 1",
                "7k/8/8/8/8/4r3/4r3/4K3 w - - 0 1",
                "7k/8/8/8/8/8/4n3/4K3 w - - 0 1",
                "7k/8/8/8/8/8/4p3/4K3 w - - 0 1",
                "8/8/8/8/8/4k3/8/4K3 w - - 0 1"}) {
            Board board = new Board();
            board.setFromFen(fen);
            MoveList legal = new MoveList();
            MoveGenerator.generateLegal(board, legal);
            assertKingMoves(board, legal);
        }
        Random random = new Random(0x51a1eL);
        MoveList scratch = new MoveList();
        int positions = 0;
        for (int game = 0; game < 32; game++) {
            Board board = new Board();
            for (int ply = 0; ply < 160; ply++) {
                String fen = board.toFen();
                long key = board.zobristKey;
                long all = board.allOccupancy;
                long[] occupancy = board.occupancy.clone();
                long[][] pieces = {board.pieceBB[0].clone(), board.pieceBB[1].clone()};
                MoveList legal = new MoveList();
                MoveGenerator.generateLegal(board, legal);
                assertKingMoves(board, legal);
                boolean actual = MoveGenerator.hasAnyLegalMove(board, scratch);
                if (actual != (legal.size > 0))
                    throw new AssertionError("legal existence mismatch at " + fen);
                if (!fen.equals(board.toFen()) || key != board.zobristKey
                        || all != board.allOccupancy
                        || !java.util.Arrays.equals(occupancy, board.occupancy)
                        || !java.util.Arrays.equals(pieces[0], board.pieceBB[0])
                        || !java.util.Arrays.equals(pieces[1], board.pieceBB[1]))
                    throw new AssertionError("legality check changed board at " + fen);
                positions++;
                if (legal.size == 0) break;
                board.makeMove(legal.get(random.nextInt(legal.size)));
            }
        }
        System.out.println("LegalMoveExistenceTest: " + positions + " positions passed");
    }

    private static void assertKingMoves(Board board, MoveList legal) {
        int king = board.kingSquare(board.sideToMove);
        boolean expected = false;
        for (int i = 0; i < legal.size; i++) {
            int move = legal.get(i);
            if (Move.from(move) == king && !Move.isCastle(move)) expected = true;
        }
        if (MoveGenerator.hasLegalKingMove(board) != expected)
            throw new AssertionError("king move existence mismatch at " + board.toFen());
    }
}

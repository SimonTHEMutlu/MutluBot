package engine;

import java.util.Arrays;

/** Checks packed undo records and transposition entries, including edge cases. */
public final class PositionStorageTest {
    public static void main(String[] args) {
        testTranspositionTable();
        testMoveRoundTrips();
        testLongHistory();
        System.out.println("PositionStorageTest passed");
    }

    private static void testTranspositionTable() {
        TranspositionTable tt = new TranspositionTable(1);
        check(tt.probe(0) == null, "empty slot");
        tt.store(0, 128, -30000, TranspositionTable.EXACT, 0xffff);
        checkProbe(tt.probe(0), 128, -30000, TranspositionTable.EXACT, 0xffff);
        tt.store(0, 5, 40, TranspositionTable.LOWER_BOUND, 123);
        checkProbe(tt.probe(0), 128, -30000, TranspositionTable.EXACT, 0xffff);
        tt.store(0, 5, 40, TranspositionTable.EXACT, 123);
        checkProbe(tt.probe(0), 5, 40, TranspositionTable.EXACT, 123);
        tt.newSearch();
        tt.store(0, 3, -32000, TranspositionTable.UPPER_BOUND, 456);
        checkProbe(tt.probe(0), 3, -32000, TranspositionTable.UPPER_BOUND, 456);
        long collision = 1L << 16; // Same slot in a 1 MB table.
        tt.store(collision, 4, 32000, TranspositionTable.EXACT, 789);
        check(tt.probe(0) == null, "collision replacement");
        checkProbe(tt.probe(collision), 4, 32000, TranspositionTable.EXACT, 789);
        tt.clear();
        check(tt.probe(collision) == null, "clear");
    }

    private static void checkProbe(TranspositionTable.Probe p, int depth, int score,
                                   int flag, int move) {
        check(p != null && p.depth == depth && p.score == score
                && p.flag == flag && p.move == move, "packed TT entry");
    }

    private static void testMoveRoundTrips() {
        roundTrip("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                Move.encode(12, 28, Move.DOUBLE_PAWN_PUSH));
        roundTrip("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1",
                Move.encode(4, 6, Move.KING_CASTLE));
        roundTrip("r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1",
                Move.encode(60, 58, Move.QUEEN_CASTLE));
        roundTrip("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1",
                Move.encode(36, 43, Move.EP_CAPTURE));
        roundTrip("4k3/P7/8/8/8/8/8/4K3 w - - 0 1",
                Move.encode(48, 56, Move.QUEEN_PROMO));
        roundTrip("1r2k3/P7/8/8/8/8/8/4K3 w - - 0 1",
                Move.encode(48, 57, Move.QUEEN_PROMO_CAPTURE));
        roundTrip("4k3/8/8/8/8/8/4q3/4K3 w - - 0 1",
                Move.encode(4, 12, Move.CAPTURE));
        Board b = new Board();
        String before = b.toFen();
        long key = b.zobristKey;
        b.makeNullMove();
        checkConsistent(b);
        b.unmakeNullMove();
        check(before.equals(b.toFen()) && key == b.zobristKey, "null move undo");
    }

    private static void roundTrip(String fen, int move) {
        Board b = new Board();
        b.setFromFen(fen);
        long key = b.zobristKey;
        b.makeMove(move);
        checkConsistent(b);
        b.unmakeMove();
        check(fen.equals(b.toFen()) && key == b.zobristKey, "move undo: " + fen);
        checkConsistent(b);
    }

    private static void testLongHistory() {
        Board b = new Board();
        String start = b.toFen();
        long key = b.zobristKey;
        int[] moves = {
                Move.encode(6, 21, Move.QUIET), Move.encode(62, 45, Move.QUIET),
                Move.encode(21, 6, Move.QUIET), Move.encode(45, 62, Move.QUIET)
        };
        for (int i = 0; i < 300; i++) b.makeMove(moves[i % moves.length]);
        checkConsistent(b);
        for (int i = 0; i < 300; i++) b.unmakeMove();
        check(start.equals(b.toFen()) && key == b.zobristKey, "long undo history");
    }

    private static void checkConsistent(Board b) {
        Board rebuilt = new Board();
        rebuilt.setFromFen(b.toFen());
        check(rebuilt.zobristKey == b.zobristKey
                && Arrays.equals(rebuilt.mailbox, b.mailbox)
                && Arrays.equals(rebuilt.occupancy, b.occupancy)
                && rebuilt.allOccupancy == b.allOccupancy, "position consistency");
        for (int c = 0; c < 2; c++)
            check(Arrays.equals(rebuilt.pieceBB[c], b.pieceBB[c]), "bitboard consistency");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

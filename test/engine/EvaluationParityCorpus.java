package engine;

import java.util.Random;

/** Stable evaluation corpus with historical raw-EP identity (not repetition identity). */
public final class EvaluationParityCorpus {
    static final String[] FENS = {
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1",
        "r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1",
        "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1",
        "4k3/8/8/8/3Pp3/8/8/4K3 b - d3 0 1",
        "1r2k3/P7/8/8/8/8/7p/4K1R1 w - - 0 1",
        "1r2k3/P7/8/8/8/8/7p/4K1R1 b - - 0 1",
        "6rk/pppppppp/8/8/8/8/QQQQQQQQ/K7 w - - 0 1",
        "1r5k/Pppppppp/8/8/8/8/QQQQQQQQ/K7 w - - 0 1",
        "4k3/8/2p5/3P4/8/8/8/4K3 w - - 0 1"
    };
    private static long hash = 0xcbf29ce484222325L;
    private static int count;
    public static void main(String[] args) {
        hash = 0xcbf29ce484222325L;
        count = 0;
        Random random = new Random(30103L);
        for (String fen : FENS) {
            Board b = new Board();
            b.setFromFen(fen);
            fingerprint(b);
            MoveList moves = new MoveList();
            MoveGenerator.generateLegal(b, moves);
            for (int i = 0; i < moves.size; i++) {
                b.makeMove(moves.get(i)); fingerprint(b); b.unmakeMove(); fingerprint(b);
            }
            for (int game = 0; game < 10; game++) {
                b.setFromFen(fen);
                int plies = 0;
                for (; plies < 100; plies++) {
                    moves.clear(); MoveGenerator.generateLegal(b, moves);
                    if (moves.size == 0) break;
                    b.makeMove(moves.get(random.nextInt(moves.size))); fingerprint(b);
                }
                while (plies-- > 0) { b.unmakeMove(); fingerprint(b); }
            }
        }
        System.out.println("EvaluationParityCorpus positions=" + count + " fingerprint=" + Long.toHexString(hash));
        if (count != 21345 || hash != 0x0efee4e365a49278L) {
            throw new AssertionError("Approved pre-incremental evaluation baseline changed: positions="
                    + count + " fingerprint=" + Long.toHexString(hash));
        }
    }
    private static void fingerprint(Board b) {
        hash = (hash ^ Evaluator.evaluate(b)) * 0x100000001b3L;
        // Canonical repetition keys intentionally omit unusable EP targets.
        // Retain the original independent position fingerprint so its approved
        // evaluator baseline is not silently replaced when hashing is fixed.
        hash = (hash ^ historicalPositionKey(b)) * 0x100000001b3L;
        count++;
    }
    private static long historicalPositionKey(Board b) {
        long key=0;
        for(int color=0;color<2;color++)for(int type=0;type<6;type++) {
            long pieces=b.pieceBB[color][type];
            while(pieces!=0){int sq=Long.numberOfTrailingZeros(pieces);pieces&=pieces-1;
                key^=Zobrist.PIECE_KEY[color][type][sq];}
        }
        for(int bit=0;bit<4;bit++)if((b.castlingRights&(1<<bit))!=0)key^=Zobrist.CASTLE_KEY[bit];
        if(b.epSquare!=-1)key^=Zobrist.EP_FILE_KEY[b.epSquare&7];
        if(b.sideToMove==Piece.BLACK)key^=Zobrist.SIDE_KEY;
        return key;
    }
}

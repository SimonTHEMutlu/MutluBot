package engine;

import java.lang.reflect.Field;
import java.util.Random;
import static engine.Piece.*;

/** Independent piece scans validate cached scores, raw phase and pawn-key restoration. */
public final class IncrementalEvaluationTest {
    private static final int[] PHASE = {0, 1, 1, 2, 4, 0};
    private static int checks;
    private static int flags;
    public static void main(String[] args) throws Exception {
        Random random = new Random(30303L);
        for (String fen : EvaluationParityCorpus.FENS) {
            Board b = new Board(); b.setFromFen(fen); check(b);
            MoveList moves = new MoveList(); MoveGenerator.generateLegal(b, moves); check(b);
            for (int i = 0; i < moves.size; i++) {
                flags |= 1 << Move.flag(moves.get(i));
                String before = b.toFen(); long key = b.zobristKey;
                b.makeMove(moves.get(i)); check(b);
                b.makeNullMove(); check(b); b.unmakeNullMove(); check(b);
                b.unmakeMove(); check(b);
                require(before.equals(b.toFen()) && key == b.zobristKey, "complete move restoration");
            }
            for (int game = 0; game < 10; game++) {
                b.setFromFen(fen); int plies = 0;
                for (; plies < 100; plies++) {
                    moves.clear(); MoveGenerator.generateLegal(b, moves); check(b);
                    if (moves.size == 0) break;
                    b.makeMove(moves.get(random.nextInt(moves.size))); check(b);
                }
                while (plies-- > 0) { b.unmakeMove(); check(b); }
                require(fen.equals(b.toFen()), "random game restoration");
            }
            b.setStartPosition(); check(b);
        }
        Board b = new Board();
        int[] cycle = {Move.encode(6,21,Move.QUIET), Move.encode(62,45,Move.QUIET),
            Move.encode(21,6,Move.QUIET), Move.encode(45,62,Move.QUIET)};
        for (int i = 0; i < 300; i++) { b.makeMove(cycle[i % 4]); check(b); }
        for (int i = 0; i < 300; i++) { b.unmakeMove(); check(b); }
        Board blocker = new Board();
        blocker.setFromFen("4k3/8/4p3/8/2NP4/2P5/8/6K1 w - - 0 1");
        check(blocker);
        long blockedTerms = Evaluator.pawnStructureTerms(blocker);
        long pawnKey = blocker.pawnZobristKey;
        blocker.makeMove(Move.encode(26, 9, Move.QUIET)); check(blocker);
        require(blocker.pawnZobristKey == pawnKey, "nonpawn blocker preserves pawn key");
        require(Evaluator.pawnStructureTerms(blocker) != blockedTerms, "blocker removal changes backward pawn term");
        blocker.unmakeMove(); check(blocker);
        require(Evaluator.pawnStructureTerms(blocker) == blockedTerms, "blocker restoration");
        Board queen = new Board();
        queen.setFromFen("4k3/8/8/8/8/8/5PqP/6K1 w - - 0 1");
        check(queen); pawnKey = queen.pawnZobristKey;
        queen.makeMove(Move.encode(6, 14, Move.CAPTURE)); check(queen);
        require(queen.pawnZobristKey == pawnKey, "queen capture preserves pawn key");
        queen.unmakeMove(); check(queen);
        require((flags & 0xff3f) == 0xff3f, "all quiet/capture/castling/EP/promotion flags covered");
        System.out.println("IncrementalEvaluationTest passed checks=" + checks);
    }
    private static void check(Board b) throws Exception {
        int material = 0, mg = 0, eg = 0, phase = 0; long pawns = 0;
        for (int color = 0; color < 2; color++) {
            int sign = color == WHITE ? 1 : -1;
            int[][] mgTable = table(color == WHITE ? "PST_MG_WHITE" : "PST_MG_BLACK");
            int[][] egTable = table(color == WHITE ? "PST_EG_WHITE" : "PST_EG_BLACK");
            for (int type = 0; type < 6; type++) {
                long pieces = b.pieceBB[color][type];
                while (pieces != 0) {
                    int sq = Long.numberOfTrailingZeros(pieces); pieces &= pieces - 1;
                    material += sign * Evaluator.PIECE_VALUE[type];
                    mg += sign * mgTable[type][sq]; eg += sign * egTable[type][sq];
                    phase += PHASE[type];
                    if (type == PAWN) pawns ^= Zobrist.PAWN_KEY[color][sq];
                }
            }
        }
        require(material == b.incrementalMaterialScore, "material");
        require(mg == b.incrementalMgPstScore, "MG PST");
        require(eg == b.incrementalEgPstScore, "EG PST");
        require(phase == b.phaseUnits && Math.min(phase,24) == Evaluator.gamePhase(b), "unclamped/clamped phase");
        require(pawns == b.pawnZobristKey, "pawn key");
        Board rebuilt = new Board(); rebuilt.setFromFen(b.toFen());
        require(rebuilt.zobristKey == b.zobristKey, "full key");
        int score = Evaluator.evaluate(b);
        require(score == Evaluator.evaluate(rebuilt), "incremental/FEN eval parity");
        checks++;
    }
    private static int[][] table(String name) throws Exception {
        Field field = Evaluator.class.getDeclaredField(name); field.setAccessible(true);
        return (int[][]) field.get(null);
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

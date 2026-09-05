package engine;

import static engine.Piece.*;
import static engine.Bitboards.*;



/**
 * A deliberately simple evaluation function: material + piece-square tables.
 * This is the classic starting point for a chess engine. Good next steps to
 * strengthen it (left as an exercise for whoever builds on this):
 *   - Tapered evaluation (separate middlegame/endgame PSTs, blended by game phase)
 *   - Pawn structure (passed/isolated/doubled pawns, pawn chains)
 *   - King safety (pawn shield, open files near king, attacker counts)
 *   - Mobility (number of legal-ish moves per piece)
 *   - Rook on open file / 7th rank, bishop pair bonus, knight outposts, etc.
 */
public final class Evaluator {
    private Evaluator() {}

    public static final int PAWN_VALUE = 100;
    public static final int KNIGHT_VALUE = 320;
    public static final int BISHOP_VALUE = 330;
    public static final int ROOK_VALUE = 500;
    public static final int QUEEN_VALUE = 900;
    public static final int[] PIECE_VALUE = {PAWN_VALUE, KNIGHT_VALUE, BISHOP_VALUE, ROOK_VALUE, QUEEN_VALUE, 0};

    public static final int MATE_SCORE = 30000;
    public static final int INFINITY_SCORE = 32000;

    // ---- Game phase (0 = pure endgame, PHASE_MAX = full opening material) ----
    private static final int PHASE_KNIGHT = 1;
    private static final int PHASE_BISHOP = 1;
    private static final int PHASE_ROOK = 2;
    private static final int PHASE_QUEEN = 4;
    public static final int PHASE_MAX = 4 * PHASE_KNIGHT + 4 * PHASE_BISHOP + 4 * PHASE_ROOK + 2 * PHASE_QUEEN; // 24

    // Tables below are given in "a8..h8, a7..h7, ... a1..h1" reading order
    // (top of a printed board down to the bottom) and converted to our
    // square indexing (a1=0 .. h8=63) at class-load time.
    private static final int[] PAWN_TABLE_RAW = {
             0,  0,   0,   0,   0,   0,  0,  0,
            50, 50,  50,  50,  50,  50, 50, 50,
            10, 10,  20,  30,  30,  20, 10, 10,
             5,  5,  10,  25,  25,  10,  5,  5,
             0,  0,   15,  25,  20,   0,  0,  0,
             5, 5, 5,   0,   0, -10, 5,  5,
             5, 10,  10, -20, -20,  10, 10,  5,
             0,  0,   0,   0,   0,   0,  0,  0
    };

    private static final int[] PAWN_TABLE_RAW_EG = {
             0,  0,   0,   0,   0,   0,  0,  0,
            52, 50,  50,  50,  50,  50, 50, 52,
            25, 20,  20,  20,  20,  20, 20, 25,
             17,  15,  15,  15,  15,  15,  15,  17,
             12,  10,   10,  10,  10,  10,   10, 12,
             7,   5,    5,   5,   5,   5,    5,   7,
             0,   0,    0,   0,   0,   0,    0,   0,
             0,   0,    0,   0,   0,   0,    0,   0
    };


    private static final int[] KNIGHT_TABLE_RAW = {
            -50, -40, -30, -30, -30, -30, -40, -50,
            -40, -20,   0,   0,   0,   0, -20, -40,
              0,   0,  10,  20,  15,  10,   0,   0,
              5,   5,  15,  17,  17,  15,   5, -30,
              5,   0,  15,  17,  17,  15,   0, -30,
              0,   5,  15,  15,  15,  20,   5,   0,
            -40, -20,   0,   5,   5,   0, -20, -40,
            -50, -40, -30, -30, -30, -30, -40, -50
    };

    private static final int[] KNIGHT_TABLE_RAW_EG = {
            -10,   5,   8,   8,   8,   8,   5, -10,
              5,   7,  13,  13,  13,  13,   7,   5,
              7,  12,  14,  15,  15,  14,  12,   7,
              5,   8,  15,  20,  20,  15,   8,   5,
              5,  10,  15,  20,  20,  15,  10,   5,
              5,   5,  10,  15,  15,  15,   5,   5,
              5,   0,   0,   5,   5,   0,   0,   5,
            -10,   4,   5,   5,   5,   5,   4, -10
    };

    private static final int[] BISHOP_TABLE_RAW = {
            -20, -10, -10, -10, -10, -10, -10, -20,
            -10,   0,   0,   0,   0,   0,   0, -10,
            -10,   0,  10,  10,  10,  10,   0, -10,
            -10,   15,  5,  10,  10,   5,  15, -10,
            -10,   0,  15,  10,  10,  15,   0, -10,
            -10,  10,  10,  10,  10,  10,  10, -10,
            -10,  10,   0,   0,   0,   0,  10, -10,
            -20, -10, -10, -10, -10, -10, -10, -20
    };

    private static final int[] BISHOP_TABLE_RAW_EG = {
              0,   0,   0,   0,   0,   0,   0,   0,
              0,   0,   0,   0,   0,   0,   0,   0,
              0,   0,  10,  10,  10,  10,   0,   0,
              0,  15,   5,  10,  10,   5,  15,   0,
              0,   0,  15,  10,  10,  15,   0,   0,
              0,  10,  10,  10,  10,  10,  10,   0,
              0,  10,  0,   0,   0,   0,  10,    0,
              0,   0,  0,   0,   0,   0,   0,   0
    };

    private static final int[] ROOK_TABLE_RAW = {
              0,  0,  0,  0,  0,  0,  0,  0,
              5, 10, 10, 10, 10, 10, 10,  5,
             -5,  0,  0,  0,  0,  0,  0, -5,
             -5,  0,  0,  0,  0,  0,  0, -5,
             2,  0,  0,  0,  0,  0,  0, 2,
             2,  2,  2,  2,  2,  2,  2, 2,
             -5,  0,  0,  0,  0,  0,  0, -5,
              0,  0,  0,  5,  5,  0,  0,  0
    };

    private static final int[] ROOK_TABLE_RAW_EG = {
              0,  0,  0,  0,  0,  0,  0,  0,
            10, 10, 10, 10, 10, 10, 10,  10,
             0,  0,  0,  0,  0,  0,  0, 0,
             0,  0,  0,  0,  0,  0,  0, 0,
             0,  0,  0,  0,  0,  0,  0, 0,
             0,  0,  0,  0,  0,  0,  0, 0,
              5,  5,  5,  5,  5,  5,  5,  5,
              0,  0,  0,  5,  5,  0,  0,  0
    };

    private static final int[] QUEEN_TABLE_RAW = {
            -20, -10, -10, -5, -5, -10, -10, -20,
            -10,   5,   5,  5,  5,   5,   5, -10,
              0,   0,   5,  5,  5,   5,   0,   0,
              5,   0,   5,  5,  5,   5,   5,   5,
              5,   0,   5,  5,  5,   5,   5,   5,
              0,   5,   5,  5,  5,   5,   0,   0,
            -10,   0,   5,  0,  0,   0,   0, -10,
            -20, -10, -10, -5, -5, -10, -10, -20
    };

    private static final int[] QUEEN_TABLE_RAW_EG = {
            -20, -10, -10, -5, -5, -10, -10, -20,
            -10,   0,   0,  0,  0,   0,   0, -10,
            -10,   0,   5,  5,  5,   5,   0, -10,
             -5,   0,   5,  5,  5,   5,   0,  -5,
              0,   0,   5,  5,  5,   5,   0,  -5,
            -10,   5,   5,  5,  5,   5,   0, -10,
            -10,   0,   5,  0,  0,   0,   0, -10,
            -20, -10, -10, -5, -5, -10, -10, -20
    };

    private static final int[] KING_TABLE_RAW = {
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -20, -30, -30, -40, -40, -30, -30, -20,
            -10, -20, -20, -20, -20, -20, -20, -10,
             20,  20,   0,   0,   0,   0,  20,  20,
             20,  30,  10,   0,   0,  10,  30,  20
    };

    private static final int[] KING_TABLE_RAW_EG = {
              0,   0,   0,   0,   0,   0,   0,   0,
              4,   7,   9,  10,  10,   9,   7,   4,
              5,   8,  12,  16,  16,   12,  8,   5,
              7,  10,  13,  20,  20,   13, 10,   7,
              7,   7,   12,  15, 15,   12,  7,   7,
              5,   5,   6,   7,   7,   6,   5,   5,
              0,  10,   0,   0,   0,   0,  10,   0,
              0,   0,   0,   0,   0,   0,   0,   0
    };



private static final int[][] PST_MG_WHITE = new int[6][64];
private static final int[][] PST_MG_BLACK = new int[6][64];
private static final int[][] PST_EG_WHITE = new int[6][64];
private static final int[][] PST_EG_BLACK = new int[6][64];

static {
    // Knight/bishop/rook/queen currently share the same table for mg and eg.
    int[][] rawMg = {PAWN_TABLE_RAW, KNIGHT_TABLE_RAW, BISHOP_TABLE_RAW, ROOK_TABLE_RAW, QUEEN_TABLE_RAW, KING_TABLE_RAW};
    int[][] rawEg = {PAWN_TABLE_RAW_EG, KNIGHT_TABLE_RAW_EG, BISHOP_TABLE_RAW_EG, ROOK_TABLE_RAW_EG, QUEEN_TABLE_RAW_EG, KING_TABLE_RAW_EG};

    for (int type = 0; type < 6; type++) {
        for (int i = 0; i < 64; i++) {
            int rank = 7 - (i / 8);
            int file = i % 8;
            int sq = rank * 8 + file;
            PST_MG_WHITE[type][sq] = rawMg[type][i];
            PST_MG_BLACK[type][sq ^ 56] = rawMg[type][i];
            PST_EG_WHITE[type][sq] = rawEg[type][i];
            PST_EG_BLACK[type][sq ^ 56] = rawEg[type][i];
        }
    }
}

    /** Score is from the perspective of the side to move (positive = good for side to move). */
    public static int evaluate(Board b) {
    int mgScore = 0;
    int egScore = 0;

    for (int type = 0; type < 6; type++) {
        int value = PIECE_VALUE[type];

        long wb = b.pieceBB[WHITE][type];
        while (wb != 0) {
            int sq = lsb(wb);
            wb &= wb - 1;
            mgScore += value + PST_MG_WHITE[type][sq];
            egScore += value + PST_EG_WHITE[type][sq];
        }
        long bb2 = b.pieceBB[BLACK][type];
        while (bb2 != 0) {
            int sq = lsb(bb2);
            bb2 &= bb2 - 1;
            mgScore -= value + PST_MG_BLACK[type][sq];
            egScore -= value + PST_EG_BLACK[type][sq];
        }
    }

    if (popcount(b.pieceBB[WHITE][BISHOP]) >= 2) { mgScore += 30; egScore += 30; }
    if (popcount(b.pieceBB[BLACK][BISHOP]) >= 2) { mgScore -= 30; egScore -= 30; }

    int phase = gamePhase(b);
    int blended = (mgScore * phase + egScore * (PHASE_MAX - phase)) / PHASE_MAX;

    return b.sideToMove == WHITE ? blended : -blended;
    }

    /** gamePhase is used in order to have multiple piece-square tables for different phases of the game */
    /** Returns a value from 0 (kings + pawns only) to PHASE_MAX (full starting material). */
    public static int gamePhase(Board b) {
    int phase = 0;
    phase += popcount(b.pieceBB[WHITE][KNIGHT] | b.pieceBB[BLACK][KNIGHT]) * PHASE_KNIGHT;
    phase += popcount(b.pieceBB[WHITE][BISHOP] | b.pieceBB[BLACK][BISHOP]) * PHASE_BISHOP;
    phase += popcount(b.pieceBB[WHITE][ROOK] | b.pieceBB[BLACK][ROOK]) * PHASE_ROOK;
    phase += popcount(b.pieceBB[WHITE][QUEEN] | b.pieceBB[BLACK][QUEEN]) * PHASE_QUEEN;
    return Math.min(phase, PHASE_MAX); // clamp: promoted pawns could otherwise push this over PHASE_MAX
    }
}

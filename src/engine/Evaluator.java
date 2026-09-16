package engine;

import static engine.Piece.*;
import static engine.Bitboards.*;



/**
 * A deliberately simple tapered evaluation function: material, piece-square
 * tables, bishop pair, passed pawns, endgame king activity, and deliberately
 * small pawn/knight activity and king-pressure terms. Good next steps:
 *   - More pawn structure (isolated/doubled pawns, pawn chains)
 *   - Broader king safety (pawn shield, open files near king, attacker counts)
 *   - Broader mobility, if it can meet the evaluator performance budget
 *   - Rook on open file / 7th rank, knight outposts, etc.
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

    // Passed-pawn bonuses indexed by relative rank: 1 = home rank, 6 = one
    // step from promotion. Advancement matters more as pieces come off because
    // there are fewer blockers and kings can support the pawn directly.
    static final int[] PASSED_PAWN_MG_BONUS = {0, 0, 5, 12, 25, 45, 80, 0};
    static final int[] PASSED_PAWN_EG_BONUS = {0, 5, 12, 25, 45, 80, 140, 0};
    // Centipawns per square of closeness (7 - king distance) to an own passer.
    // More advanced passers receive more value from direct king support.
    static final int[] PASSED_PAWN_KING_PROXIMITY = {0, 1, 1, 2, 3, 4, 5, 0};
    // Centipawns per square of closeness to each enemy pawn. This is added only
    // to the endgame score and therefore fades out as non-pawn material returns.
    static final int ENEMY_PAWN_KING_PROXIMITY = 2;
    private static final long[][] PASSED_PAWN_MASK = new long[2][64];

    // Modest, allocation-free activity terms. Pawns and kings deliberately
    // receive no mobility score: pawn pushes require move-generation logic and
    // pseudo-legal king mobility would reward moves into attacked squares.
    // The full N/B/R/Q candidate missed the performance and node-stability
    // gates. Keep only the cheap knight signal in production; the generic
    // attack helpers remain available to focused tests.
    private static final int[] MOBILITY_MG_WEIGHT = {0, 1, 0, 0, 0, 0};
    private static final int[] MOBILITY_EG_WEIGHT = {0, 0, 0, 0, 0, 0};
    // Middlegame points per king-ring square controlled by each attacker. Direct
    // pressure is symmetric: attacks near our king are subtracted when the
    // opponent's pressure is removed from our own pressure.
    // Likewise, retain only pawn and knight pressure. Scaling the symmetric net
    // term keeps the feature below the search-instability threshold observed in
    // the broader experiments while still allowing real non-zero contributions.
    private static final int[] KING_RING_MG_WEIGHT = {1, 1, 0, 0, 0, 0};
    private static final int KING_PRESSURE_MG_CAP = 8;
    private static final int ACTIVITY_SCALE = 8;

    // Tables below are given in "a8..h8, a7..h7, ... a1..h1" reading order
    // (top of a printed board down to the bottom) and converted to our
    // square indexing (a1=0 .. h8=63) at class-load time.
    private static final int[] PAWN_TABLE_RAW = {
             0,  0,   0,   0,   0,   0,  0,  0,
            50, 50,  50,  50,  50,  50, 50, 50,
            10, 10,  20,  30,  30,  20, 10, 10,
             5,  5,  10,  25,  25,  10,  5,  5,
             0,  0,   15,  25,  20,   0,  0,  0,
             5, 5, 10,   0,   0, -10, 5,  5,
             5, 10,  -5, -20, -20,  10, 10,  5,
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
            -20, -10, -5, -3, -3, -5, -10, -20,
            -10, -5,   0,   0,   0,   0, -5, -10,
              0,   0,  10,  20,  15,  10,   0,   0,
              5,   5,  15,  17,  17,  15,   5,  5,
              5,   0,  15,  17,  17,  15,   0, 5,
              0,   5,  15,  15,  15,  20,   5,   0,
            -5, -2,  0,   14,   14,   0, -2, -5,
            -10, -5,   0,   0,    0,   0, -5,  -10
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
            -10,   0,   10,  0,  0,   0,   0, -10,
            -20, -10, -5, 0, 0, -5, -10, -20
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
            -20, -10,   0,   5,   5,   0, -10, -20,
            -10,   0,  10,  15,  15,  10,   0, -10,
              0,  10,  20,  30,  30,  20,  10,   0,
              5,  15,  30,  40,  40,  30,  15,   5,
              5,  15,  30,  40,  40,  30,  15,   5,
              0,  10,  20,  30,  30,  20,  10,   0,
            -10,   0,  10,  15,  15,  10,   0, -10,
            -20, -10,   0,   5,   5,   0, -10, -20
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

    // A pawn is passed when no enemy pawn is ahead of it on its own file or
    // either adjacent file. Precompute those three-file forward spans so the
    // evaluator only needs one bitboard intersection per pawn.
    for (int sq = 0; sq < 64; sq++) {
        int rank = sq >>> 3;
        int file = sq & 7;
        for (int targetFile = Math.max(0, file - 1); targetFile <= Math.min(7, file + 1); targetFile++) {
            for (int targetRank = rank + 1; targetRank < 8; targetRank++) {
                PASSED_PAWN_MASK[WHITE][sq] |= 1L << (targetRank * 8 + targetFile);
            }
            for (int targetRank = rank - 1; targetRank >= 0; targetRank--) {
                PASSED_PAWN_MASK[BLACK][sq] |= 1L << (targetRank * 8 + targetFile);
            }
        }
    }
}

    /** Score is from the perspective of the side to move (positive = good for side to move). */
    public static int evaluate(Board b) {
    int mgScore = 0;
    int egScore = 0;
    int whiteKingSq = b.kingSquare(WHITE);
    int blackKingSq = b.kingSquare(BLACK);

    for (int type = 0; type < 6; type++) {
        int value = PIECE_VALUE[type];

        long wb = b.pieceBB[WHITE][type];
        while (wb != 0) {
            int sq = lsb(wb);
            wb &= wb - 1;
            mgScore += value + PST_MG_WHITE[type][sq];
            egScore += value + PST_EG_WHITE[type][sq];
            if (type == PAWN) {
                int relativeRank = sq >>> 3;
                if (isPassedPawn(WHITE, b.pieceBB[BLACK][PAWN], sq)) {
                    mgScore += PASSED_PAWN_MG_BONUS[relativeRank];
                    egScore += PASSED_PAWN_EG_BONUS[relativeRank];
                    egScore += kingProximity(whiteKingSq, sq)
                            * PASSED_PAWN_KING_PROXIMITY[relativeRank];
                }
                egScore -= kingProximity(blackKingSq, sq) * ENEMY_PAWN_KING_PROXIMITY;
            }
        }
        long bb2 = b.pieceBB[BLACK][type];
        while (bb2 != 0) {
            int sq = lsb(bb2);
            bb2 &= bb2 - 1;
            mgScore -= value + PST_MG_BLACK[type][sq];
            egScore -= value + PST_EG_BLACK[type][sq];
            if (type == PAWN) {
                int relativeRank = 7 - (sq >>> 3);
                if (isPassedPawn(BLACK, b.pieceBB[WHITE][PAWN], sq)) {
                    mgScore -= PASSED_PAWN_MG_BONUS[relativeRank];
                    egScore -= PASSED_PAWN_EG_BONUS[relativeRank];
                    egScore -= kingProximity(blackKingSq, sq)
                            * PASSED_PAWN_KING_PROXIMITY[relativeRank];
                }
                egScore += kingProximity(whiteKingSq, sq) * ENEMY_PAWN_KING_PROXIMITY;
            }
        }
    }

    if (popcount(b.pieceBB[WHITE][BISHOP]) >= 2) { mgScore += 30; egScore += 30; }
    if (popcount(b.pieceBB[BLACK][BISHOP]) >= 2) { mgScore -= 30; egScore -= 30; }

    int phase = gamePhase(b);
    long activity = activityTerms(b, phase);
    mgScore += (int) (activity >> 32);
    egScore += (int) activity;

    int blended = (mgScore * phase + egScore * (PHASE_MAX - phase)) / PHASE_MAX;

    return b.sideToMove == WHITE ? blended : -blended;
    }

    /** Package-private for focused evaluator tests. */
    static boolean isPassedPawn(Board b, int color, int sq) {
        return isPassedPawn(color, b.pieceBB[opposite(color)][PAWN], sq);
    }

    private static boolean isPassedPawn(int color, long enemyPawns, int sq) {
        return (PASSED_PAWN_MASK[color][sq] & enemyPawns) == 0;
    }

    /**
     * Returns the white-relative activity terms packed as MG in the high int
     * and EG in the low int. This is package-private for focused evaluator
     * tests; the search hot path unpacks it without allocating an object.
     */
    static long activityTerms(Board b) {
        return activityTerms(b, gamePhase(b));
    }

    private static long activityTerms(Board b, int phase) {
        // With no non-pawn material there is no N/B/R/Q mobility, and the
        // middlegame-only king-pressure term would be fully tapered out.
        if (phase == 0) return 0L;

        int whiteMg = 0, blackMg = 0;
        int whiteEg = 0, blackEg = 0;

        int whiteKingSq = b.kingSquare(WHITE);
        int blackKingSq = b.kingSquare(BLACK);
        long whiteKingRing = KING_ATTACKS[whiteKingSq];
        long blackKingRing = KING_ATTACKS[blackKingSq];

        long whitePawnControls = pawnControlMap(b.pieceBB[WHITE][PAWN], WHITE);
        long blackPawnControls = pawnControlMap(b.pieceBB[BLACK][PAWN], BLACK);
        long whiteMobilityArea = ~b.occupancy[WHITE] & ~blackPawnControls;
        long blackMobilityArea = ~b.occupancy[BLACK] & ~whitePawnControls;

        int whitePressure = popcount(whitePawnControls & blackKingRing)
                * KING_RING_MG_WEIGHT[PAWN];
        int blackPressure = popcount(blackPawnControls & whiteKingRing)
                * KING_RING_MG_WEIGHT[PAWN];

        for (int type = KNIGHT; type <= QUEEN; type++) {
            if (MOBILITY_MG_WEIGHT[type] == 0 && MOBILITY_EG_WEIGHT[type] == 0
                    && KING_RING_MG_WEIGHT[type] == 0) continue;
            long pieces = b.pieceBB[WHITE][type];
            while (pieces != 0) {
                int sq = lsb(pieces);
                pieces &= pieces - 1;
                long attacks = pieceAttacks(type, sq, b.allOccupancy);
                int mobility = popcount(attacks & whiteMobilityArea);
                whiteMg += mobility * MOBILITY_MG_WEIGHT[type];
                whiteEg += mobility * MOBILITY_EG_WEIGHT[type];
                whitePressure += popcount(attacks & blackKingRing)
                        * KING_RING_MG_WEIGHT[type];
            }

            pieces = b.pieceBB[BLACK][type];
            while (pieces != 0) {
                int sq = lsb(pieces);
                pieces &= pieces - 1;
                long attacks = pieceAttacks(type, sq, b.allOccupancy);
                int mobility = popcount(attacks & blackMobilityArea);
                blackMg += mobility * MOBILITY_MG_WEIGHT[type];
                blackEg += mobility * MOBILITY_EG_WEIGHT[type];
                blackPressure += popcount(attacks & whiteKingRing)
                        * KING_RING_MG_WEIGHT[type];
            }
        }

        whitePressure = Math.min(whitePressure, KING_PRESSURE_MG_CAP);
        blackPressure = Math.min(blackPressure, KING_PRESSURE_MG_CAP);

        int mg = (whiteMg - blackMg + whitePressure - blackPressure) / ACTIVITY_SCALE;
        int eg = (whiteEg - blackEg) / ACTIVITY_SCALE;
        return ((long) mg << 32) | (eg & 0xffffffffL);
    }

    /** Total safe pseudo-mobility for one non-pawn, non-king piece type. */
    static int safeMobilityCount(Board b, int color, int type) {
        if (type < KNIGHT || type > QUEEN) return 0;
        long enemyPawnControls = pawnControlMap(b.pieceBB[opposite(color)][PAWN], opposite(color));
        long mobilityArea = ~b.occupancy[color] & ~enemyPawnControls;
        int count = 0;
        long pieces = b.pieceBB[color][type];
        while (pieces != 0) {
            int sq = lsb(pieces);
            pieces &= pieces - 1;
            long attacks = pieceAttacks(type, sq, b.allOccupancy);
            count += popcount(attacks & mobilityArea);
        }
        return count;
    }

    /** Package-private reference calculation used only by focused tests. */
    static int kingPressureMg(Board b, int attackingColor) {
        int defendingColor = opposite(attackingColor);
        int defendingKingSq = b.kingSquare(defendingColor);
        long attackingPawnControls = pawnControlMap(b.pieceBB[attackingColor][PAWN], attackingColor);
        long defendingKingRing = KING_ATTACKS[defendingKingSq];
        int pressure = popcount(attackingPawnControls & defendingKingRing)
                * KING_RING_MG_WEIGHT[PAWN];

        for (int type = KNIGHT; type <= QUEEN; type++) {
            long pieces = b.pieceBB[attackingColor][type];
            while (pieces != 0) {
                int sq = lsb(pieces);
                pieces &= pieces - 1;
                pressure += popcount(pieceAttacks(type, sq, b.allOccupancy) & defendingKingRing)
                        * KING_RING_MG_WEIGHT[type];
            }
        }
        return Math.min(pressure, KING_PRESSURE_MG_CAP);
    }

    private static long pieceAttacks(int type, int sq, long occupancy) {
        switch (type) {
            case KNIGHT: return KNIGHT_ATTACKS[sq];
            case BISHOP: return bishopAttacks(sq, occupancy);
            case ROOK: return rookAttacks(sq, occupancy);
            case QUEEN: return queenAttacks(sq, occupancy);
            default: return 0L;
        }
    }

    private static long pawnControlMap(long pawns, int color) {
        if (color == WHITE) {
            return ((pawns & ~FILE_A) << 7) | ((pawns & ~FILE_H) << 9);
        }
        return ((pawns & ~FILE_H) >>> 7) | ((pawns & ~FILE_A) >>> 9);
    }

    /** Number of king moves saved versus the maximum possible distance of seven. */
    private static int kingProximity(int kingSq, int pawnSq) {
        int fileDistance = Math.abs((kingSq & 7) - (pawnSq & 7));
        int rankDistance = Math.abs((kingSq >>> 3) - (pawnSq >>> 3));
        return 7 - Math.max(fileDistance, rankDistance);
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

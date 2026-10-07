package engine;

import static engine.Piece.*;
import static engine.Bitboards.*;



/**
 * A deliberately simple tapered evaluation function: material, piece-square
 * tables, bishop pair, pawn structure and passers, rook-file activity, endgame
 * king activity, and a bounded middlegame king-danger term. Good next steps:
 *   - Broader king safety (pawn shield, open files near king, attacker counts)
 *   - Broader mobility, if it can meet the evaluator performance budget
 *   - Rook on the 7th rank, knight outposts, etc.
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
    private static final long[] ADJACENT_FILE_MASK = new long[8];

    // Rooks gain modest tapered activity for working on pawn-free files.
    private static final int ROOK_OPEN_FILE_MG = 16;
    private static final int ROOK_OPEN_FILE_EG = 12;
    private static final int ROOK_SEMIOPEN_FILE_MG = 8;
    private static final int ROOK_SEMIOPEN_FILE_EG = 6;

    // Mobility is deliberately disabled in this isolated king-safety pass. The
    // attack maps below are used only for king danger, avoiding a second set of
    // slider walks and keeping this experiment measurable.
    // Piece indices are P=0, N=1, B=2, R=3, Q=4, K=5. Pawns are counted
    // separately below because their attack map is color-dependent.
    static final int[] KING_ATTACK_UNIT = {0, 2, 2, 3, 5, 0};
    private static final int[] KING_DANGER_TABLE = {
            0, 0, 2, 4, 7, 11, 16, 22,
            29, 37, 46, 56, 68, 82, 98, 116
    };
    static final int KING_DANGER_MG_CAP = 140;
    private static final int KING_ATTACKED_SQUARE_SCALE = 1;
    private static final int KING_ATTACKER_SCALE = 6;
    private static final int KING_NO_QUEEN_NUMERATOR = 3;
    private static final int KING_NO_QUEEN_DENOMINATOR = 4;
    private static final int KING_OPEN_FILE_MG = 5;
    private static final int KING_SEMIOPEN_FILE_MG = 2;
    private static final long[] KING_INNER_ZONE = new long[64];
    private static final long[] KING_OUTER_ZONE = new long[64];

    // Middlegame-only pawn shelter. One pawn advanced a single rank beyond
    // immediate cover is free; additional looseness is nonlinear, and a
    // missing pawn is worse than a far-advanced pawn. Normal phase blending
    // fades the white-relative term completely out of pawn-only endings.
    private static final int SHELTER_EXTRA_LOOSE_MG = 4;
    private static final int SHELTER_FAR_PAWN_MG = 10;
    private static final int SHELTER_MISSING_PAWN_MG = 14;
    private static final int SHELTER_MULTIPLE_WEAK_MG = 4;
    static final int KING_SHELTER_MG_CAP = 40;
    // Missing forward cover costs a small amount per neighboring king file
    // while the opposing queen is present. It fades with the normal MG phase.
    private static final int QUEEN_PRESENT_MISSING_COVER_MG = 6;

    // Pawn structure is deliberately modest. A non-central isolated pawn costs
    // 10 MG / 16 EG; an isolated c/f pawn costs 5 / 14, while d/e IQPs cost
    // only 2 MG / 12 EG. The central MG relief preserves attacking chances
    // while pieces remain, and the normal phase blend makes IQPs more costly
    // as material comes off. A true backward pawn on a semi-open file costs
    // 8 MG / 12 EG. Isolated pawns are excluded from this term to avoid stacking
    // two labels on the same pawn.
    private static final int ISOLATED_FLANK_MG = 10;
    private static final int ISOLATED_FLANK_EG = 16;
    private static final int ISOLATED_WING_MG = 5;
    private static final int ISOLATED_WING_EG = 14;
    private static final int ISOLATED_CENTER_MG = 2;
    private static final int ISOLATED_CENTER_EG = 12;
    private static final int BACKWARD_SEMIOPEN_MG = 8;
    private static final int BACKWARD_SEMIOPEN_EG = 12;

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
              7,   10,  15,  17,  17,  15,   10,  7,
              7,   0,  15,  17,  17,  15,   0, 7,
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

    for (int file = 0; file < 8; file++) {
        if (file > 0) ADJACENT_FILE_MASK[file] |= FILE_MASK[file - 1];
        if (file < 7) ADJACENT_FILE_MASK[file] |= FILE_MASK[file + 1];
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

        // The king zone is the adjacent ring plus the complete distance-two
        // Chebyshev ring. It is deliberately color-independent and therefore
        // mirror-symmetric.
        int kingRank = sq >>> 3;
        int kingFile = sq & 7;
        KING_INNER_ZONE[sq] = KING_ATTACKS[sq];
        long outerZone = 0L;
        for (int zoneRank = Math.max(0, kingRank - 2); zoneRank <= Math.min(7, kingRank + 2); zoneRank++) {
            for (int zoneFile = Math.max(0, kingFile - 2); zoneFile <= Math.min(7, kingFile + 2); zoneFile++) {
                if (Math.max(Math.abs(zoneRank - kingRank), Math.abs(zoneFile - kingFile)) == 2) {
                    outerZone |= 1L << (zoneRank * 8 + zoneFile);
                }
            }
        }
        KING_OUTER_ZONE[sq] = outerZone;
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

    long pawnStructure = pawnStructureTerms(b);
    mgScore += (int) (pawnStructure >> 32);
    egScore += (int) pawnStructure;

    long rookFiles = rookFileTerms(b);
    mgScore += (int) (rookFiles >> 32);
    egScore += (int) rookFiles;

    int phase = gamePhase(b);
    if (phase != 0) {
        mgScore += kingShelterPenaltyMg(b, BLACK) - kingShelterPenaltyMg(b, WHITE);
    }
    long activity = activityTerms(b, phase);
    mgScore += (int) (activity >> 32);
    egScore += (int) activity;

    int blended = (mgScore * phase + egScore * (PHASE_MAX - phase)) / PHASE_MAX;

    return b.sideToMove == WHITE ? blended : -blended;
    }

    /** Returns white-relative pawn structure packed as signed MG/EG scores. */
    static long pawnStructureTerms(Board b) {
        long white = pawnStructureForSide(b, WHITE);
        long black = pawnStructureForSide(b, BLACK);
        int mg = (int) (white >> 32) - (int) (black >> 32);
        int eg = (int) white - (int) black;
        return ((long) mg << 32) | (eg & 0xffffffffL);
    }

    /** Returns white-relative tapered activity for rooks on open/semi-open files. */
    static long rookFileTerms(Board b) {
        int mg = 0;
        int eg = 0;
        for (int color = WHITE; color <= BLACK; color++) {
            long ownPawns = b.pieceBB[color][PAWN];
            long enemyPawns = b.pieceBB[opposite(color)][PAWN];
            long rooks = b.pieceBB[color][ROOK];
            int sign = color == WHITE ? 1 : -1;
            while (rooks != 0L) {
                int square = lsb(rooks);
                rooks &= rooks - 1;
                long file = FILE_MASK[square & 7];
                if ((ownPawns & file) != 0L) continue;
                boolean open = (enemyPawns & file) == 0L;
                mg += sign * (open ? ROOK_OPEN_FILE_MG : ROOK_SEMIOPEN_FILE_MG);
                eg += sign * (open ? ROOK_OPEN_FILE_EG : ROOK_SEMIOPEN_FILE_EG);
            }
        }
        return ((long) mg << 32) | (eg & 0xffffffffL);
    }

    /** Returns one side's signed pawn-structure score packed as MG/EG. */
    private static long pawnStructureForSide(Board b, int color) {
        int mg = 0;
        int eg = 0;
        long pawns = b.pieceBB[color][PAWN];
        long friendly = pawns;
        while (pawns != 0L) {
            int sq = lsb(pawns);
            pawns &= pawns - 1;
            int file = sq & 7;
            long adjacentFiles = ADJACENT_FILE_MASK[file];
            if ((friendly & adjacentFiles) == 0L) {
                mg -= (file == 3 || file == 4) ? ISOLATED_CENTER_MG
                        : (file == 2 || file == 5) ? ISOLATED_WING_MG : ISOLATED_FLANK_MG;
                eg -= (file == 3 || file == 4) ? ISOLATED_CENTER_EG
                        : (file == 2 || file == 5) ? ISOLATED_WING_EG : ISOLATED_FLANK_EG;
            } else if (isBackwardPawn(b, color, sq)) {
                mg -= BACKWARD_SEMIOPEN_MG;
                eg -= BACKWARD_SEMIOPEN_EG;
            }
        }
        return ((long) mg << 32) | (eg & 0xffffffffL);
    }

    /**
     * True for a non-isolated pawn on a file with no enemy pawn, when it has
     * no adjacent pawn able to support its advance and an enemy pawn controls
     * the square immediately ahead. A neighboring pawn one rank behind only
     * counts as potential support when its advance square is clear and not
     * controlled by an enemy pawn. A home-rank pawn two ranks behind can also
     * catch up with a legal two-square push when both path squares are clear.
     */
    static boolean isBackwardPawn(Board b, int color, int sq) {
        long friendly = b.pieceBB[color][PAWN];
        long enemy = b.pieceBB[opposite(color)][PAWN];
        int file = sq & 7;
        int rank = sq >>> 3;
        if ((enemy & FILE_MASK[file]) != 0L) return false;
        long neighbors = friendly & ADJACENT_FILE_MASK[file];
        if (neighbors == 0L) return false;
        int step = color == WHITE ? 8 : -8;
        int front = sq + step;
        if (front < 0 || front >= 64) return false;
        if ((enemy & PAWN_ATTACKS[color][front]) == 0L) return false;
        return !hasPotentialAdjacentPawnSupport(b, color, rank, step, neighbors, enemy);
    }

    private static boolean hasPotentialAdjacentPawnSupport(Board b, int color, int rank,
                                                            int step, long neighbors, long enemy) {
        while (neighbors != 0L) {
            int neighbor = lsb(neighbors);
            neighbors &= neighbors - 1;
            int neighborRank = neighbor >>> 3;
            if (neighborRank == rank) return true;
            if ((color == WHITE && neighborRank == rank - 1)
                    || (color == BLACK && neighborRank == rank + 1)) {
                if (isClearSafePawnSupport(b, color, neighbor + step, enemy)) return true;
            }
            int homeRank = color == WHITE ? 1 : 6;
            if (neighborRank == homeRank
                    && ((color == WHITE && rank == homeRank + 2)
                    || (color == BLACK && rank == homeRank - 2))) {
                int transitSquare = neighbor + step;
                int supportSquare = transitSquare + step;
                if (isClearSafeDoublePush(b, color, transitSquare, supportSquare, enemy)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isClearSafePawnSupport(Board b, int color, int supportSquare, long enemy) {
        if (supportSquare < 0 || supportSquare >= 64) return false;
        long bit = 1L << supportSquare;
        return (b.allOccupancy & bit) == 0L
                && (enemy & PAWN_ATTACKS[color][supportSquare]) == 0L;
    }

    private static boolean isClearSafeDoublePush(Board b, int color, int transitSquare,
                                                  int supportSquare, long enemy) {
        if (transitSquare < 0 || transitSquare >= 64 || supportSquare < 0 || supportSquare >= 64) return false;
        long path = (1L << transitSquare) | (1L << supportSquare);
        return (b.allOccupancy & path) == 0L
                && (enemy & PAWN_ATTACKS[color][supportSquare]) == 0L;
    }

    /** Package-private for focused evaluator tests. */
    static boolean isPassedPawn(Board b, int color, int sq) {
        return isPassedPawn(color, b.pieceBB[opposite(color)][PAWN], sq);
    }

    private static boolean isPassedPawn(int color, long enemyPawns, int sq) {
        return (PASSED_PAWN_MASK[color][sq] & enemyPawns) == 0;
    }

    /** Returns white-relative, middlegame-only king danger packed as MG/EG. */
    static long activityTerms(Board b) {
        return activityTerms(b, gamePhase(b));
    }

    private static long activityTerms(Board b, int phase) {
        if (phase == 0) return 0L;
        int mg = kingDangerMg(b, WHITE) - kingDangerMg(b, BLACK);
        return ((long) mg << 32);
    }

    /** Package-private reference calculation used by focused evaluator tests. */
    static int kingPressureMg(Board b, int attackingColor) {
        return kingDangerMg(b, attackingColor);
    }

    /**
     * Returns a diagnostic summary of threats against {@code defendingColor}.
     * This is deliberately not part of normal evaluation. The packed fields are
     * safe checking moves (bits 0-7), current tapered king pressure in MG cp
     * (8-15), plausible pawn breaks toward the king (16-23), accessible open
     * king-adjacent files (24-27), and accessible semi-open files (28-31).
     *
     * Safe checks are legal checking moves whose destination is not geometrically
     * attacked by the defender after the move. This conservative test can miss
     * checks where a nominal defender is pinned. Castling checks are omitted.
     * The method uses only primitive locals and temporarily makes/unmakes moves;
     * it allocates no per-call objects and restores the complete visible board
     * state before returning.
     */
    static long threatMetrics(Board b, int defendingColor) {
        int attackingColor = opposite(defendingColor);
        int safeChecks = countSafeCheckingMoves(b, attackingColor, defendingColor);
        int pressure = kingPressureMg(b, attackingColor);
        int pawnBreaks = countKingwardPawnBreaks(b, attackingColor, defendingColor);
        int openFiles = accessibleKingFiles(b, attackingColor, defendingColor, true);
        int semiOpenFiles = accessibleKingFiles(b, attackingColor, defendingColor, false);
        return ((long) Math.min(255, safeChecks))
                | ((long) Math.min(255, pressure) << 8)
                | ((long) Math.min(255, pawnBreaks) << 16)
                | ((long) Math.min(15, openFiles) << 24)
                | ((long) Math.min(15, semiOpenFiles) << 28);
    }

    static int threatSafeCheckCount(long metrics) { return (int) (metrics & 0xffL); }
    static int threatKingPressure(long metrics) { return (int) ((metrics >>> 8) & 0xffL); }
    static int threatPawnBreakCount(long metrics) { return (int) ((metrics >>> 16) & 0xffL); }
    static int threatOpenKingFileCount(long metrics) { return (int) ((metrics >>> 24) & 0xfL); }
    static int threatSemiOpenKingFileCount(long metrics) { return (int) ((metrics >>> 28) & 0xfL); }

    private static int countSafeCheckingMoves(Board b, int attackingColor, int defendingColor) {
        int originalSide = b.sideToMove;
        int originalEpSquare = b.epSquare;
        long originalKey = b.zobristKey;
        if (originalSide != attackingColor) {
            // En-passant belongs only to the actual side to move. A diagnostic
            // side flip must not let the hypothetical attacker inherit it.
            if (b.epSquare != -1) {
                b.zobristKey ^= Zobrist.EP_FILE_KEY[b.epSquare & 7];
                b.epSquare = -1;
            }
            b.sideToMove = attackingColor;
            b.zobristKey ^= Zobrist.SIDE_KEY;
        }

        int count = 0;
        long own = b.occupancy[attackingColor];
        long enemy = b.occupancy[defendingColor] & ~b.pieceBB[defendingColor][KING];
        for (int type = KNIGHT; type <= KING; type++) {
            long pieces = b.pieceBB[attackingColor][type];
            while (pieces != 0L) {
                int from = lsb(pieces);
                pieces &= pieces - 1;
                long targets = pieceAttacks(type, from, b.allOccupancy) & ~own
                        & ~b.pieceBB[defendingColor][KING];
                while (targets != 0L) {
                    int to = lsb(targets);
                    targets &= targets - 1;
                    int flag = (enemy & (1L << to)) != 0L ? Move.CAPTURE : Move.QUIET;
                    if (isSafeCheckingMove(b, Move.encode(from, to, flag), to, attackingColor, defendingColor)) {
                        count++;
                    }
                }
            }
        }

        count += countSafePawnChecks(b, attackingColor, defendingColor, enemy);
        b.sideToMove = originalSide;
        b.epSquare = originalEpSquare;
        b.zobristKey = originalKey;
        return count;
    }

    private static int countSafePawnChecks(Board b, int attackingColor, int defendingColor, long enemy) {
        int count = 0;
        int forward = attackingColor == WHITE ? 8 : -8;
        int promotionRank = attackingColor == WHITE ? 7 : 0;
        long pawns = b.pieceBB[attackingColor][PAWN];
        while (pawns != 0L) {
            int from = lsb(pawns);
            pawns &= pawns - 1;
            int one = from + forward;
            if (one >= 0 && one < 64 && (b.allOccupancy & (1L << one)) == 0L) {
                if ((one >>> 3) == promotionRank) {
                    if (safePromotionCheck(b, from, one, attackingColor, defendingColor, false)) count++;
                } else {
                    if (isSafeCheckingMove(b, Move.encode(from, one, Move.QUIET), one,
                            attackingColor, defendingColor)) count++;
                    int startRank = attackingColor == WHITE ? 1 : 6;
                    int two = from + 2 * forward;
                    if ((from >>> 3) == startRank && (b.allOccupancy & (1L << two)) == 0L
                            && isSafeCheckingMove(b, Move.encode(from, two, Move.DOUBLE_PAWN_PUSH), two,
                            attackingColor, defendingColor)) count++;
                }
            }

            long captures = PAWN_ATTACKS[attackingColor][from] & enemy
                    & ~b.pieceBB[defendingColor][KING];
            while (captures != 0L) {
                int to = lsb(captures);
                captures &= captures - 1;
                if ((to >>> 3) == promotionRank) {
                    if (safePromotionCheck(b, from, to, attackingColor, defendingColor, true)) count++;
                } else if (isSafeCheckingMove(b, Move.encode(from, to, Move.CAPTURE), to,
                        attackingColor, defendingColor)) {
                    count++;
                }
            }

            if (b.epSquare >= 0 && (PAWN_ATTACKS[attackingColor][from] & (1L << b.epSquare)) != 0L
                    && isSafeCheckingMove(b, Move.encode(from, b.epSquare, Move.EP_CAPTURE), b.epSquare,
                    attackingColor, defendingColor)) count++;
        }
        return count;
    }

    private static boolean safePromotionCheck(Board b, int from, int to, int attackingColor,
                                               int defendingColor, boolean capture) {
        int flagBase = capture ? Move.KNIGHT_PROMO_CAPTURE : Move.KNIGHT_PROMO;
        for (int piece = KNIGHT; piece <= QUEEN; piece++) {
            int flag = flagBase + (piece - KNIGHT);
            if (isSafeCheckingMove(b, Move.encode(from, to, flag), to, attackingColor, defendingColor)) return true;
        }
        return false;
    }

    private static boolean isSafeCheckingMove(Board b, int move, int to,
                                               int attackingColor, int defendingColor) {
        b.makeMove(move);
        boolean safeCheck = !b.isInCheck(attackingColor)
                && b.isInCheck(defendingColor)
                && !b.isSquareAttacked(to, defendingColor);
        b.unmakeMove();
        return safeCheck;
    }

    private static int countKingwardPawnBreaks(Board b, int attackingColor, int defendingColor) {
        int king = b.kingSquare(defendingColor);
        int kingFile = king & 7;
        int forward = attackingColor == WHITE ? 8 : -8;
        int startRank = attackingColor == WHITE ? 1 : 6;
        int count = 0;
        long pawns = b.pieceBB[attackingColor][PAWN];
        long enemy = b.occupancy[defendingColor];
        while (pawns != 0L) {
            int from = lsb(pawns);
            pawns &= pawns - 1;
            int fromDistance = chebyshevDistance(from, king);
            int one = from + forward;
            if (one >= 0 && one < 64 && (b.allOccupancy & (1L << one)) == 0L
                    && Math.abs((one & 7) - kingFile) <= 1
                    && chebyshevDistance(one, king) < fromDistance) {
                count++;
                int two = from + 2 * forward;
                if ((from >>> 3) == startRank && (b.allOccupancy & (1L << two)) == 0L
                        && Math.abs((two & 7) - kingFile) <= 1
                        && chebyshevDistance(two, king) < fromDistance) count++;
            }
            long captures = PAWN_ATTACKS[attackingColor][from] & enemy;
            while (captures != 0L) {
                int to = lsb(captures);
                captures &= captures - 1;
                if (Math.abs((to & 7) - kingFile) <= 1
                        && chebyshevDistance(to, king) < fromDistance) count++;
            }
        }
        return count;
    }

    private static int chebyshevDistance(int a, int b) {
        return Math.max(Math.abs((a & 7) - (b & 7)), Math.abs((a >>> 3) - (b >>> 3)));
    }

    private static int accessibleKingFiles(Board b, int attackingColor, int defendingColor, boolean open) {
        int kingFile = b.kingSquare(defendingColor) & 7;
        long heavyPieces = b.pieceBB[attackingColor][ROOK] | b.pieceBB[attackingColor][QUEEN];
        long attackingPawns = b.pieceBB[attackingColor][PAWN];
        long defendingPawns = b.pieceBB[defendingColor][PAWN];
        int count = 0;
        for (int file = Math.max(0, kingFile - 1); file <= Math.min(7, kingFile + 1); file++) {
            long mask = FILE_MASK[file];
            if ((heavyPieces & mask) == 0L || (attackingPawns & mask) != 0L) continue;
            boolean isOpen = (defendingPawns & mask) == 0L;
            if (isOpen == open) count++;
        }
        return count;
    }

    private static int kingDangerMg(Board b, int attackingColor) {
        int defendingColor = opposite(attackingColor);
        // Moved and exchanged/captured N/B/R/Q/K pieces both clear a slot;
        // pawns never do. Skip attack generation until at least one slot clears.
        int clearedSlots = developedOrExchangedNonPawnSlots(b, attackingColor);
        int scale = kingDevelopmentScale(clearedSlots);
        if (scale == 0) return 0;

        long innerZone = KING_INNER_ZONE[b.kingSquare(defendingColor)];
        long outerZone = KING_OUTER_ZONE[b.kingSquare(defendingColor)];
        long defendingOccupancy = b.occupancy[defendingColor];
        long attackedZone = 0L;
        int attackUnits = 0;
        int attackers = 0;
        int outerOnlyUnits = 0;
        int outerOnlyAttackers = 0;
        long outerOnlyZone = 0L;
        int occupiedOuterUnits = 0;
        int occupiedOuterAttackers = 0;
        long occupiedOuterZone = 0L;

        long pawns = b.pieceBB[attackingColor][PAWN];
        while (pawns != 0L) {
            int sq = lsb(pawns);
            pawns &= pawns - 1;
            long attacks = PAWN_ATTACKS[attackingColor][sq];
            long innerHit = attacks & innerZone;
            long outerHit = attacks & outerZone;
            if (innerHit != 0L) {
                attackUnits++;
                attackers++;
                attackedZone |= innerHit;
            } else if (outerHit != 0L) {
                outerOnlyUnits++;
                outerOnlyAttackers++;
                outerOnlyZone |= outerHit;
                long occupiedHit = outerHit & defendingOccupancy;
                if (occupiedHit != 0L) {
                    occupiedOuterUnits++;
                    occupiedOuterAttackers++;
                    occupiedOuterZone |= occupiedHit;
                }
            }
        }

        for (int type = KNIGHT; type <= QUEEN; type++) {
            long pieces = b.pieceBB[attackingColor][type];
            while (pieces != 0L) {
                int sq = lsb(pieces);
                pieces &= pieces - 1;
                long attacks = pieceAttacks(type, sq, b.allOccupancy);
                long innerHit = attacks & innerZone;
                long outerHit = attacks & outerZone;
                if (innerHit != 0L) {
                    attackUnits += KING_ATTACK_UNIT[type];
                    attackers++;
                    attackedZone |= innerHit;
                } else if (outerHit != 0L) {
                    outerOnlyUnits += KING_ATTACK_UNIT[type];
                    outerOnlyAttackers++;
                    outerOnlyZone |= outerHit;
                    long occupiedHit = outerHit & defendingOccupancy;
                    if (occupiedHit != 0L) {
                        occupiedOuterUnits += KING_ATTACK_UNIT[type];
                        occupiedOuterAttackers++;
                        occupiedOuterZone |= occupiedHit;
                    }
                }
            }
        }

        // A lone ray into an empty distance-two square is not king pressure.
        // Occupied outer-zone contact is meaningful on its own; otherwise the
        // outer ring requires at least two distinct attacking pieces.
        if (outerOnlyAttackers >= 2) {
            attackUnits += outerOnlyUnits;
            attackers += outerOnlyAttackers;
            attackedZone |= outerOnlyZone;
        } else if (occupiedOuterAttackers != 0) {
            attackUnits += occupiedOuterUnits;
            attackers += occupiedOuterAttackers;
            attackedZone |= occupiedOuterZone;
        }

        int danger = KING_DANGER_TABLE[Math.min(attackUnits, KING_DANGER_TABLE.length - 1)];
        danger += Math.min(18, Math.max(0, attackers - 1) * KING_ATTACKER_SCALE);
        danger += Math.min(12, popcount(attackedZone) * KING_ATTACKED_SQUARE_SCALE);
        if (b.pieceBB[attackingColor][QUEEN] == 0L) {
            danger = danger * KING_NO_QUEEN_NUMERATOR / KING_NO_QUEEN_DENOMINATOR;
        }
        danger += openFileDangerMg(b, attackingColor, defendingColor);
        int cappedDanger = Math.min(danger, KING_DANGER_MG_CAP);
        if (cappedDanger == 0) return 0;
        return (cappedDanger * scale + 63) / 64;
    }

    /** Quadratic king-danger multiplier in sixty-fourths, for focused tests. */
    static int kingDevelopmentScale(int clearedSlots) {
        int slots = Math.max(0, Math.min(8, clearedSlots));
        return slots * slots;
    }

    private static int developedOrExchangedNonPawnSlots(Board b, int color) {
        long homeRank = color == WHITE ? RANK_1 : RANK_8;
        int survivingHomePieces = 0;
        for (int type = KNIGHT; type <= KING; type++) {
            survivingHomePieces += popcount(b.pieceBB[color][type] & homeRank);
        }
        return Math.max(0, Math.min(8, 8 - survivingHomePieces));
    }

    /** Positive MG penalty against {@code color}; package-private for tests. */
    static int kingShelterPenaltyMg(Board b, int color) {
        int kingSq = b.kingSquare(color);
        int kingRank = kingSq >>> 3;
        int kingFile = kingSq & 7;
        int queenCoverPenalty = queenPresentMissingCoverPenaltyMg(b, color);
        // The shelter model is for castled/wing kings. Central d/e-file kings
        // are handled by development and open-center terms, not wing shelter.
        if (kingFile >= 3 && kingFile <= 4) return queenCoverPenalty;
        int firstFile = Math.max(0, Math.min(5, kingFile - 1));
        int forward = color == WHITE ? 1 : -1;
        long pawns = b.pieceBB[color][PAWN];

        int looseFiles = 0;
        int farFiles = 0;
        int missingFiles = 0;
        int weakFiles = 0;

        for (int file = firstFile; file < firstFile + 3; file++) {
            int distance = 0;
            for (int rank = kingRank + forward, step = 1;
                    rank >= 0 && rank < 8; rank += forward, step++) {
                if ((pawns & (1L << (rank * 8 + file))) != 0L) {
                    distance = step;
                    break;
                }
            }

            if (distance == 1) continue;
            weakFiles++;
            if (distance == 2) looseFiles++;
            else if (distance >= 3) farFiles++;
            else missingFiles++;
        }

        int penalty = Math.max(0, looseFiles - 1) * SHELTER_EXTRA_LOOSE_MG
                + farFiles * SHELTER_FAR_PAWN_MG
                + missingFiles * SHELTER_MISSING_PAWN_MG;
        if (weakFiles >= 2) penalty += SHELTER_MULTIPLE_WEAK_MG;
        return Math.min(penalty + queenCoverPenalty, KING_SHELTER_MG_CAP);
    }

    /**
     * Charges for absent forward pawn cover on king-adjacent files while the
     * opponent still has a queen. A pawn anywhere ahead on its file counts;
     * edge kings naturally have only two relevant files. This is kept
     * separate from the distance-sensitive wing shelter term and is tapered
     * by the evaluator's normal middlegame phase blend.
     */
    static int queenPresentMissingCoverPenaltyMg(Board b, int color) {
        int enemy = opposite(color);
        if (b.pieceBB[enemy][QUEEN] == 0L) return 0;
        int kingSq = b.kingSquare(color);
        int kingRank = kingSq >>> 3;
        int kingFile = kingSq & 7;
        int forward = color == WHITE ? 1 : -1;
        long pawns = b.pieceBB[color][PAWN];
        int missing = 0;
        for (int file = Math.max(0, kingFile - 1); file <= Math.min(7, kingFile + 1); file++) {
            boolean found = false;
            for (int rank = kingRank + forward; rank >= 0 && rank < 8; rank += forward) {
                if ((pawns & (1L << (rank * 8 + file))) != 0L) {
                    found = true;
                    break;
                }
            }
            if (!found) missing++;
        }
        return missing * QUEEN_PRESENT_MISSING_COVER_MG;
    }

    static int openFileDangerMg(Board b, int attackingColor, int defendingColor) {
        // Pawnless files are only a king-safety concern when the attacker has
        // a rook or queen available to use them. Without a heavy piece, this
        // feature otherwise punishes open files even in harmless endings.
        if ((b.pieceBB[attackingColor][ROOK] | b.pieceBB[attackingColor][QUEEN]) == 0L) return 0;
        int kingFile = b.kingSquare(defendingColor) & 7;
        long attackingPawns = b.pieceBB[attackingColor][PAWN];
        long defendingPawns = b.pieceBB[defendingColor][PAWN];
        int danger = 0;
        for (int file = Math.max(0, kingFile - 1); file <= Math.min(7, kingFile + 1); file++) {
            long fileMask = FILE_MASK[file];
            // From the attacker's perspective, its own pawn closes the file
            // to its rook/queen. Otherwise the file is open if the defender
            // also has no pawn, or semi-open if a defending pawn can be
            // targeted on that file.
            if ((attackingPawns & fileMask) != 0L) continue;
            danger += (defendingPawns & fileMask) == 0L
                    ? KING_OPEN_FILE_MG : KING_SEMIOPEN_FILE_MG;
        }
        return Math.min(16, danger);
    }

    private static long pieceAttacks(int type, int sq, long occupancy) {
        switch (type) {
            case KNIGHT: return KNIGHT_ATTACKS[sq];
            case BISHOP: return bishopAttacks(sq, occupancy);
            case ROOK: return rookAttacks(sq, occupancy);
            case QUEEN: return queenAttacks(sq, occupancy);
            case KING: return KING_ATTACKS[sq];
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

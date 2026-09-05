package engine;

/**
 * Bitboard constants and precomputed attack tables.
 *
 * Square indexing: 0 = a1, 1 = b1, ... 7 = h1, 8 = a2, ... 63 = h8
 * (little-endian rank-file mapping, the standard used by most bitboard engines).
 *
 * square = rank * 8 + file,  file = square & 7,  rank = square >>> 3
 */
public final class Bitboards {

    private Bitboards() {}

    public static final long FILE_A = 0x0101010101010101L;
    public static final long FILE_H = FILE_A << 7;
    public static final long RANK_1 = 0xFFL;
    public static final long RANK_8 = RANK_1 << 56;
    public static final long RANK_2 = RANK_1 << 8;
    public static final long RANK_4 = RANK_1 << 24;
    public static final long RANK_5 = RANK_1 << 32;
    public static final long RANK_7 = RANK_1 << 48;

    public static final long[] FILE_MASK = new long[8];
    public static final long[] RANK_MASK = new long[8];

    // Ray directions, indices:
    public static final int N = 0, NE = 1, E = 2, SE = 3, S = 4, SW = 5, W = 6, NW = 7;
    // Directions with strictly increasing target square index as distance grows.
    public static final boolean[] POSITIVE_DIR = {true, true, true, false, false, false, false, true};

    public static final long[][] RAY = new long[8][64];      // RAY[dir][sq]
    public static final long[] KNIGHT_ATTACKS = new long[64];
    public static final long[] KING_ATTACKS = new long[64];
    public static final long[][] PAWN_ATTACKS = new long[2][64]; // [color][sq]

    static {
        for (int f = 0; f < 8; f++) FILE_MASK[f] = FILE_A << f;
        for (int r = 0; r < 8; r++) RANK_MASK[r] = RANK_1 << (8 * r);

        int[] dFile = {0, 1, 1, 1, 0, -1, -1, -1};
        int[] dRank = {1, 1, 0, -1, -1, -1, 0, 1};

        for (int sq = 0; sq < 64; sq++) {
            int file = sq & 7, rank = sq >>> 3;
            for (int dir = 0; dir < 8; dir++) {
                long mask = 0L;
                int f = file + dFile[dir];
                int r = rank + dRank[dir];
                while (f >= 0 && f < 8 && r >= 0 && r < 8) {
                    mask |= 1L << (r * 8 + f);
                    f += dFile[dir];
                    r += dRank[dir];
                }
                RAY[dir][sq] = mask;
            }

            // Knight
            long kn = 0L;
            int[][] kOff = {{1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}};
            for (int[] o : kOff) {
                int f = file + o[0], r = rank + o[1];
                if (f >= 0 && f < 8 && r >= 0 && r < 8) kn |= 1L << (r * 8 + f);
            }
            KNIGHT_ATTACKS[sq] = kn;

            // King
            long kg = 0L;
            for (int df = -1; df <= 1; df++) {
                for (int dr = -1; dr <= 1; dr++) {
                    if (df == 0 && dr == 0) continue;
                    int f = file + df, r = rank + dr;
                    if (f >= 0 && f < 8 && r >= 0 && r < 8) kg |= 1L << (r * 8 + f);
                }
            }
            KING_ATTACKS[sq] = kg;

            // Pawn attacks
            long wp = 0L, bp = 0L;
            if (file > 0 && rank < 7) wp |= 1L << (sq + 7);
            if (file < 7 && rank < 7) wp |= 1L << (sq + 9);
            if (file < 7 && rank > 0) bp |= 1L << (sq - 7);
            if (file > 0 && rank > 0) bp |= 1L << (sq - 9);
            PAWN_ATTACKS[0][sq] = wp; // white
            PAWN_ATTACKS[1][sq] = bp; // black
        }
    }

    public static int popcount(long bb) {
        return Long.bitCount(bb);
    }

    /** Index of least significant set bit. Caller must ensure bb != 0. */
    public static int lsb(long bb) {
        return Long.numberOfTrailingZeros(bb);
    }

    /** Index of most significant set bit. Caller must ensure bb != 0. */
    public static int msb(long bb) {
        return 63 - Long.numberOfLeadingZeros(bb);
    }

    public static long attacksAlongRay(int dir, int sq, long occupancy) {
        long attacks = RAY[dir][sq];
        long blockers = attacks & occupancy;
        if (blockers == 0L) return attacks;
        int blockerSq = POSITIVE_DIR[dir] ? lsb(blockers) : msb(blockers);
        return attacks ^ RAY[dir][blockerSq];
    }

    public static long bishopAttacks(int sq, long occupancy) {
        return attacksAlongRay(NE, sq, occupancy) | attacksAlongRay(NW, sq, occupancy)
             | attacksAlongRay(SE, sq, occupancy) | attacksAlongRay(SW, sq, occupancy);
    }

    public static long rookAttacks(int sq, long occupancy) {
        return attacksAlongRay(N, sq, occupancy) | attacksAlongRay(S, sq, occupancy)
             | attacksAlongRay(E, sq, occupancy) | attacksAlongRay(W, sq, occupancy);
    }

    public static long queenAttacks(int sq, long occupancy) {
        return bishopAttacks(sq, occupancy) | rookAttacks(sq, occupancy);
    }

    public static String squareName(int sq) {
        int file = sq & 7, rank = sq >>> 3;
        return "" + (char) ('a' + file) + (char) ('1' + rank);
    }

    public static int squareFromName(String name) {
        int file = name.charAt(0) - 'a';
        int rank = name.charAt(1) - '1';
        return rank * 8 + file;
    }

    public static void printBB(long bb) {
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            for (int file = 0; file < 8; file++) {
                int sq = rank * 8 + file;
                sb.append(((bb >>> sq) & 1L) != 0 ? '1' : '.');
                sb.append(' ');
            }
            sb.append('\n');
        }
        System.out.println(sb);
    }
}

package engine;

/**
 * Moves are packed into a single int:
 *   bits 0-5   : from square (0-63)
 *   bits 6-11  : to square (0-63)
 *   bits 12-15 : flag (see constants below)
 *
 * Flag encoding (standard "0000..1111" scheme used by many bitboard engines):
 *   0  quiet move
 *   1  double pawn push
 *   2  king castle
 *   3  queen castle
 *   4  capture
 *   5  en-passant capture
 *   8  knight promotion
 *   9  bishop promotion
 *   10 rook promotion
 *   11 queen promotion
 *   12 knight promotion capture
 *   13 bishop promotion capture
 *   14 rook promotion capture
 *   15 queen promotion capture
 *
 * For promotion flags, (flag & 3) gives the promoted piece type offset:
 *   0 -> KNIGHT, 1 -> BISHOP, 2 -> ROOK, 3 -> QUEEN  (see Piece constants, KNIGHT=1..QUEEN=4)
 */
public final class Move {
    private Move() {}

    public static final int QUIET = 0;
    public static final int DOUBLE_PAWN_PUSH = 1;
    public static final int KING_CASTLE = 2;
    public static final int QUEEN_CASTLE = 3;
    public static final int CAPTURE = 4;
    public static final int EP_CAPTURE = 5;
    public static final int KNIGHT_PROMO = 8;
    public static final int BISHOP_PROMO = 9;
    public static final int ROOK_PROMO = 10;
    public static final int QUEEN_PROMO = 11;
    public static final int KNIGHT_PROMO_CAPTURE = 12;
    public static final int BISHOP_PROMO_CAPTURE = 13;
    public static final int ROOK_PROMO_CAPTURE = 14;
    public static final int QUEEN_PROMO_CAPTURE = 15;

    public static final int NONE = 0; // the "no move" sentinel (a1a1 quiet, never generated legally as a real move)

    public static int encode(int from, int to, int flag) {
        return from | (to << 6) | (flag << 12);
    }

    public static int from(int move) {
        return move & 0x3F;
    }

    public static int to(int move) {
        return (move >> 6) & 0x3F;
    }

    public static int flag(int move) {
        return (move >> 12) & 0xF;
    }

    public static boolean isCapture(int move) {
        int f = flag(move);
        return f == CAPTURE || f == EP_CAPTURE || f >= 12;
    }

    public static boolean isPromotion(int move) {
        return flag(move) >= 8;
    }

    public static boolean isCastle(int move) {
        int f = flag(move);
        return f == KING_CASTLE || f == QUEEN_CASTLE;
    }

    public static boolean isEnPassant(int move) {
        return flag(move) == EP_CAPTURE;
    }

    public static boolean isDoublePush(int move) {
        return flag(move) == DOUBLE_PAWN_PUSH;
    }

    /** Returns promotion piece type (KNIGHT..QUEEN) for a promotion move. */
    public static int promotionPieceType(int move) {
        return (flag(move) & 3) + Piece.KNIGHT;
    }

    public static String toUci(int move) {
        String s = Bitboards.squareName(from(move)) + Bitboards.squareName(to(move));
        if (isPromotion(move)) {
            int pt = promotionPieceType(move);
            char c = Character.toLowerCase(Piece.SYMBOL_WHITE[pt]);
            s += c;
        }
        return s;
    }

    @Override
    public String toString() {
        return "Move.toString should not be called directly; use Move.toUci(int)";
    }
}

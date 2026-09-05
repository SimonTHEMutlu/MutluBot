package engine;

public final class Piece {
    private Piece() {}

    public static final int PAWN = 0, KNIGHT = 1, BISHOP = 2, ROOK = 3, QUEEN = 4, KING = 5;
    public static final int WHITE = 0, BLACK = 1;

    public static final char[] SYMBOL_WHITE = {'P', 'N', 'B', 'R', 'Q', 'K'};
    public static final char[] SYMBOL_BLACK = {'p', 'n', 'b', 'r', 'q', 'k'};

    public static int opposite(int color) {
        return color ^ 1;
    }

    public static char toChar(int color, int type) {
        return color == WHITE ? SYMBOL_WHITE[type] : SYMBOL_BLACK[type];
    }
}

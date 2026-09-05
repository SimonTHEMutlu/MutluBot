package engine;

import java.util.Random;

public final class Zobrist {
    private Zobrist() {}

    public static final long[][][] PIECE_KEY = new long[2][6][64]; // [color][type][sq]
    public static final long[] CASTLE_KEY = new long[4]; // WK, WQ, BK, BQ
    public static final long[] EP_FILE_KEY = new long[8];
    public static final long SIDE_KEY;

    static {
        Random rnd = new Random(0x5DEECE66DL); // fixed seed for reproducibility
        for (int c = 0; c < 2; c++)
            for (int t = 0; t < 6; t++)
                for (int s = 0; s < 64; s++)
                    PIECE_KEY[c][t][s] = rnd.nextLong();

        for (int i = 0; i < 4; i++) CASTLE_KEY[i] = rnd.nextLong();
        for (int i = 0; i < 8; i++) EP_FILE_KEY[i] = rnd.nextLong();
        SIDE_KEY = rnd.nextLong();
    }
}

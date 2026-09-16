package engine;

import java.util.Arrays;

public class TranspositionTable {

    public static final int EXACT = 0, LOWER_BOUND = 1, UPPER_BOUND = 2;

    // One key and one packed value per slot: 16 bytes instead of an Entry
    // object plus a reference. The valid bit permits a zero Zobrist key.
    private static final long VALID = 1L << 58;
    private static final int SCORE_SHIFT = 16;
    private static final int DEPTH_SHIFT = 32;
    private static final int FLAG_SHIFT = 40;
    private static final int AGE_SHIFT = 42;
    private static final long AGE_MASK = 0xffffL;

    private long[] keys;
    private long[] values;
    private int mask;
    private int currentAge;

    public TranspositionTable(int sizeMb) {
        resize(sizeMb);
    }

    public void resize(int sizeMb) {
        long bytes = (long) sizeMb * 1024 * 1024;
        long numEntries = Math.max(1024, bytes / (Long.BYTES * 2));
        int power = Integer.highestOneBit((int) Math.min(numEntries, 1L << 30));
        keys = new long[power];
        values = new long[power];
        mask = power - 1;
        currentAge = 0;
    }

    public void clear() {
        Arrays.fill(values, 0L);
        currentAge = 0;
    }

    public void newSearch() {
        // Clear before the 16-bit generation wraps, so stale entries cannot
        // look as though they came from the current search.
        if (++currentAge > AGE_MASK) clear();
    }

    public void store(long key, int depth, int score, int flag, int move) {
        int idx = (int) key & mask;
        long old = values[idx];
        if ((old & VALID) != 0 && keys[idx] == key
                && depthOf(old) > depth && age(old) == currentAge && flag != EXACT) {
            return;
        }
        // Engine depths fit in eight bits; scores stay within +/-32000.
        long packed = VALID | ((long) move & 0xffffL)
                | ((long) score & 0xffffL) << SCORE_SHIFT
                | ((long) depth & 0xffL) << DEPTH_SHIFT
                | ((long) flag & 3L) << FLAG_SHIFT
                | ((long) currentAge & AGE_MASK) << AGE_SHIFT;
        keys[idx] = key;
        values[idx] = packed;
    }

    /** Returns the entry for this key, or null if not present. */
    public Probe probe(long key) {
        long packed = probePacked(key);
        if (packed != 0) {
            return new Probe(depthOf(packed), scoreOf(packed), flagOf(packed), moveOf(packed));
        }
        return null;
    }

    /** Allocation-free lookup for the search hot path; zero means no entry. */
    public long probePacked(long key) {
        int idx = (int) key & mask;
        long packed = values[idx];
        return (packed & VALID) != 0 && keys[idx] == key ? packed : 0;
    }

    public static int depthOf(long packed) {
        return (int) (packed >>> DEPTH_SHIFT) & 0xff;
    }

    public static int scoreOf(long packed) {
        return (short) (packed >>> SCORE_SHIFT);
    }

    public static int flagOf(long packed) {
        return (int) (packed >>> FLAG_SHIFT) & 3;
    }

    public static int moveOf(long packed) {
        return (int) packed & 0xffff;
    }

    /** True when this entry was written during the current top-level search. */
    public boolean isCurrentGeneration(long packed) {
        return packed != 0 && age(packed) == currentAge;
    }

    private static int age(long packed) {
        return (int) (packed >>> AGE_SHIFT) & (int) AGE_MASK;
    }

    public static class Probe {
        public final int depth, score, flag, move;
        Probe(int depth, int score, int flag, int move) {
            this.depth = depth;
            this.score = score;
            this.flag = flag;
            this.move = move;
        }
    }
}

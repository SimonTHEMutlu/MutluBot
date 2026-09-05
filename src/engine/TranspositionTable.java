package engine;

public class TranspositionTable {

    public static final int EXACT = 0, LOWER_BOUND = 1, UPPER_BOUND = 2;

    private static class Entry {
        long key;
        int depth;
        int score;
        int flag;
        int move;
        int age;
    }

    private Entry[] table;
    private int mask;
    private int currentAge;

    public TranspositionTable(int sizeMb) {
        resize(sizeMb);
    }

    public void resize(int sizeMb) {
        long bytes = (long) sizeMb * 1024 * 1024;
        long entryBytes = 40; // rough estimate per Entry object incl. overhead
        int numEntries = (int) Math.max(1024, bytes / entryBytes);
        int power = Integer.highestOneBit(numEntries);
        table = new Entry[power];
        mask = power - 1;
        currentAge = 0;
    }

    public void clear() {
        table = new Entry[table.length];
        currentAge = 0;
    }

    public void newSearch() {
        currentAge++;
    }

    public void store(long key, int depth, int score, int flag, int move) {
        int idx = (int) (key & mask);
        Entry e = table[idx];
        if (e == null) {
            e = new Entry();
            table[idx] = e;
        } else if (e.key == key && e.depth > depth && e.age == currentAge) {
            // Keep deeper existing entry from the same search generation unless this is exact.
            if (flag != EXACT) return;
        }
        e.key = key;
        e.depth = depth;
        e.score = score;
        e.flag = flag;
        e.move = move;
        e.age = currentAge;
    }

    /** Returns the entry for this key, or null if not present. Caller reads fields immediately (not thread-safe across probes). */
    public Probe probe(long key) {
        int idx = (int) (key & mask);
        Entry e = table[idx];
        if (e != null && e.key == key) {
            return new Probe(e.depth, e.score, e.flag, e.move);
        }
        return null;
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

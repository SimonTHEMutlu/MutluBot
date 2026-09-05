package engine;

public class MoveList {
    public int[] moves = new int[256];
    public int size = 0;

    public void add(int move) {
        if (size == moves.length) {
            int[] bigger = new int[moves.length * 2];
            System.arraycopy(moves, 0, bigger, 0, moves.length);
            moves = bigger;
        }
        moves[size++] = move;
    }

    public void clear() {
        size = 0;
    }

    public int get(int i) {
        return moves[i];
    }
}

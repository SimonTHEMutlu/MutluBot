package engine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Production root initialization must not suppress descendant SEE stages. */
public final class BishopCaptureDemotionTest {
    public static void main(String[] args) throws Exception {
        Board root = new Board();
        root.setFromFen("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 3 3");
        Search search = new Search(new TranspositionTable(1));
        search.setProphylaxisProbeEnabled(false);
        search.search(root, 1, -1, null);
        Field active = Search.class.getDeclaredField("rootBishopPreferenceActive");
        active.setAccessible(true);
        require(active.getBoolean(search), "production search must activate root bishop preference");
        MoveList rootMoves = new MoveList();
        MoveGenerator.generateLegal(root, rootMoves);
        boolean bonus = false;
        for (int i = 0; i < rootMoves.size; i++) {
            if (Move.toUci(rootMoves.get(i)).equals("f1c4")) {
                bonus = Evaluator.openingBishopDevelopmentBonus(root, rootMoves.get(i)) > 0;
            }
        }
        require(bonus, "eligible bishop candidate keeps its temporary root bonus");

        // Direct descendant fixture: retain the actual production-initialized Search.
        Board descendant = new Board();
        descendant.setFromFen("4k3/8/8/4r3/4p3/8/4Q3/4K3 w - - 0 1");
        String before = descendant.toFen();
        MoveList moves = new MoveList();
        MoveGenerator.generatePseudoLegal(descendant, moves, false);
        int[] scores = new int[256];
        Method score = Search.class.getDeclaredMethod("scoreMoves", Board.class, MoveList.class,
                int.class, int.class, int[].class);
        score.setAccessible(true);
        score.invoke(search, descendant, moves, Move.NONE, 3, scores);
        Method cached = Search.class.getDeclaredMethod("captureSeeFromScore", int.class);
        cached.setAccessible(true);
        boolean badSeen = false;
        for (int i = 0; i < moves.size; i++) {
            int next = search.selectNextMove(descendant, moves, scores, i, 10, 3);
            int move = moves.moves[next], value = scores[next];
            moves.moves[next] = moves.moves[i]; scores[next] = scores[i];
            moves.moves[i] = move; scores[i] = value;
            if (Move.toUci(move).equals("e2e4")) {
                badSeen = true;
                int see = (Integer) cached.invoke(null, value);
                require(see == -800, "losing capture retains exact cached SEE for LMR");
                require(search.lmrReductionForMove(move, 10, 10, 3, Move.NONE,
                        false, false, false, see) > 0, "cached losing SEE reaches LMR");
            } else if (!Move.isCapture(move) && !Move.isPromotion(move)) {
                require(!badSeen, "all ordinary quiets precede losing capture under root preference");
            }
        }
        require(badSeen, "losing capture must be retained");
        require(before.equals(descendant.toFen()), "ordering must preserve descendant board");
        System.out.println("BishopCaptureDemotionTest passed");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

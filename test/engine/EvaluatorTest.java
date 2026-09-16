package engine;

import static engine.Piece.BLACK;
import static engine.Piece.WHITE;

/** Regression tests for tapered evaluation terms. */
public final class EvaluatorTest {
    public static void main(String[] args) {
        testPassedPawnDetection();
        testPassedPawnValues();
        testPassedPawnScoreIsApplied();
        testEndgameKingCentralization();
        testOwnPassedPawnProximity();
        testEnemyPawnProximityAndPhaseTaper();
        testSafeMobilityByPiece();
        testEnemyPawnControlledMobilityIsExcluded();
        testRawPinnedMobilityIsIntentional();
        testPerAttackerKingRingControl();
        testKingPressureCap();
        testKingPressureIsMiddlegameOnly();
        testActivityColorSymmetry();
        testColorAndSideToMoveSymmetry();
        System.out.println("EvaluatorTest passed");
    }

    private static void testPassedPawnDetection() {
        Board board = board("7k/8/8/3P4/8/8/8/K7 w - - 0 1");
        check(Evaluator.isPassedPawn(board, WHITE, square("d5")), "unopposed white passer");

        board = board("7k/8/2p5/3P4/8/8/8/K7 w - - 0 1");
        check(!Evaluator.isPassedPawn(board, WHITE, square("d5")), "enemy pawn ahead on adjacent file");

        board = board("7k/8/3p4/3P4/8/8/8/K7 w - - 0 1");
        check(!Evaluator.isPassedPawn(board, WHITE, square("d5")), "enemy pawn ahead on same file");

        board = board("7k/8/5p2/3P4/8/8/8/K7 w - - 0 1");
        check(Evaluator.isPassedPawn(board, WHITE, square("d5")), "enemy pawn outside passed-pawn span");

        board = board("7k/8/8/3P4/2p5/8/8/K7 w - - 0 1");
        check(Evaluator.isPassedPawn(board, WHITE, square("d5")), "enemy pawn behind passer");

        board = board("7k/8/8/8/3p4/2P5/8/K7 b - - 0 1");
        check(!Evaluator.isPassedPawn(board, BLACK, square("d4")), "black adjacent-file blocker");
    }

    private static void testPassedPawnValues() {
        for (int rank = 2; rank <= 6; rank++) {
            check(Evaluator.PASSED_PAWN_MG_BONUS[rank]
                            > Evaluator.PASSED_PAWN_MG_BONUS[rank - 1],
                    "middlegame bonus rises through relative rank " + rank);
            check(Evaluator.PASSED_PAWN_EG_BONUS[rank]
                            > Evaluator.PASSED_PAWN_EG_BONUS[rank - 1],
                    "endgame bonus rises through relative rank " + rank);
            check(Evaluator.PASSED_PAWN_EG_BONUS[rank]
                            >= Evaluator.PASSED_PAWN_MG_BONUS[rank],
                    "endgame bonus is at least middlegame bonus on relative rank " + rank);
        }
        check(Evaluator.PASSED_PAWN_MG_BONUS[6] == 80, "middlegame sixth-rank value");
        check(Evaluator.PASSED_PAWN_EG_BONUS[6] == 140, "endgame sixth-rank value");
    }

    private static void testPassedPawnScoreIsApplied() {
        // Moving the black pawn from d6 to e6 changes no material or PST score:
        // it only stops blocking the white c5 pawn's passed-pawn span. The f-pawns
        // keep every other pawn's passed status unchanged in both positions.
        Board blockedEndgame = board("7k/5p2/3p4/2P5/8/8/5P2/K7 w - - 0 1");
        Board passedEndgame = board("7k/5p2/4p3/2P5/8/8/5P2/K7 w - - 0 1");
        check(Evaluator.evaluate(passedEndgame) - Evaluator.evaluate(blockedEndgame) == 54,
                "endgame passer base and king-support bonuses are applied");

        Board blockedMiddlegame = board("nnbbrrqk/5p2/3p4/2P5/8/8/5P2/KQRRBBNN w - - 0 1");
        Board passedMiddlegame = board("nnbbrrqk/5p2/4p3/2P5/8/8/5P2/KQRRBBNN w - - 0 1");
        check(Evaluator.gamePhase(passedMiddlegame) == Evaluator.PHASE_MAX, "full middlegame phase");
        check(evaluateWithoutActivity(passedMiddlegame)
                        - evaluateWithoutActivity(blockedMiddlegame) == 25,
                "fourth-rank middlegame passer bonus is applied");
    }

    private static void testEndgameKingCentralization() {
        Board corner = board("7k/8/8/8/8/8/8/K7 w - - 0 1");
        Board centerD4 = board("7k/8/8/8/3K4/8/8/8 w - - 0 1");
        Board centerD5 = board("7k/8/8/3K4/8/8/8/8 w - - 0 1");
        check(Evaluator.evaluate(centerD4) - Evaluator.evaluate(corner) == 60,
                "endgame center is worth 60 centipawns over a corner");
        check(Evaluator.evaluate(centerD4) == Evaluator.evaluate(centerD5),
                "endgame king table is vertically symmetric");
    }

    private static void testOwnPassedPawnProximity() {
        Board closer = board("7k/8/P7/8/3K4/8/8/8 w - - 0 1");
        Board farther = board("7k/8/P7/8/4K3/8/8/8 w - - 0 1");
        check(Evaluator.evaluate(closer) - Evaluator.evaluate(farther) == 4,
                "king gains four centipawns for one step toward a fifth-rank passer");
    }

    private static void testEnemyPawnProximityAndPhaseTaper() {
        Board closerEndgame = board("7k/8/p7/8/3K4/8/8/8 w - - 0 1");
        Board fartherEndgame = board("7k/8/p7/8/4K3/8/8/8 w - - 0 1");
        check(Evaluator.evaluate(closerEndgame) - Evaluator.evaluate(fartherEndgame) == 2,
                "king gains two centipawns for one step toward an enemy pawn");

        Board closerMiddlegame = board("nnbbrrqk/8/p7/8/3K4/8/8/1QRRBBNN w - - 0 1");
        Board fartherMiddlegame = board("nnbbrrqk/8/p7/8/4K3/8/8/1QRRBBNN w - - 0 1");
        check(Evaluator.gamePhase(closerMiddlegame) == Evaluator.PHASE_MAX,
                "proximity taper test has full middlegame phase");
        check(evaluateWithoutActivity(closerMiddlegame)
                        == evaluateWithoutActivity(fartherMiddlegame),
                "enemy-pawn proximity is fully tapered out in the middlegame");
    }

    private static void testSafeMobilityByPiece() {
        check(Evaluator.safeMobilityCount(
                        board("k7/8/8/8/3N4/8/8/7K w - - 0 1"), WHITE, Piece.KNIGHT) == 8,
                "central knight has eight safe mobility squares");
        check(Evaluator.safeMobilityCount(
                        board("k7/8/8/8/3B4/8/8/7K w - - 0 1"), WHITE, Piece.BISHOP) == 13,
                "central bishop has thirteen safe mobility squares");
        check(Evaluator.safeMobilityCount(
                        board("k7/8/8/8/3R4/8/8/7K w - - 0 1"), WHITE, Piece.ROOK) == 14,
                "central rook has fourteen safe mobility squares");
        check(Evaluator.safeMobilityCount(
                        board("k7/8/8/8/3Q4/8/8/7K w - - 0 1"), WHITE, Piece.QUEEN) == 27,
                "central queen has twenty-seven safe mobility squares");
    }

    private static void testEnemyPawnControlledMobilityIsExcluded() {
        Board board = board("k7/8/4p3/8/3N4/8/8/7K w - - 0 1");
        check(Evaluator.safeMobilityCount(board, WHITE, Piece.KNIGHT) == 7,
                "enemy-pawn-controlled knight destination is excluded");
    }

    private static void testRawPinnedMobilityIsIntentional() {
        Board board = board("4r1k1/8/8/8/8/8/4N3/4K3 w - - 0 1");
        check(Evaluator.safeMobilityCount(board, WHITE, Piece.KNIGHT) == 6,
                "raw mobility intentionally includes a pinned knight's destinations");
    }

    private static void testPerAttackerKingRingControl() {
        Board oneKnight = board("7k/8/5N2/8/8/8/8/K7 w - - 0 1");
        Board overlappingKnight = board("5N1k/8/5N2/8/8/8/8/K7 w - - 0 1");
        check(Evaluator.kingPressureMg(oneKnight, WHITE) == 2,
                "one knight scores one point for each controlled ring square");
        check(Evaluator.kingPressureMg(overlappingKnight, WHITE) == 3,
                "overlapping control by a second attacker is counted directly");
    }

    private static void testKingPressureCap() {
        Board board = board("4NN1k/4N3/4NNQN/5NNN/8/8/8/K7 w - - 0 1");
        check(Evaluator.kingPressureMg(board, WHITE) == 8,
                "direct king pressure is capped at eight centipawns");
    }

    private static void testKingPressureIsMiddlegameOnly() {
        Board pawnPressure = board("7k/8/5P2/8/8/8/8/K7 w - - 0 1");
        check(Evaluator.kingPressureMg(pawnPressure, WHITE) == 1,
                "pawn control contributes once to direct king-ring pressure");
        long terms = Evaluator.activityTerms(pawnPressure);
        int middlegame = (int) (terms >> 32);
        int endgame = (int) terms;
        check(middlegame == 0, "pure-endgame king pressure work is skipped");
        check(endgame == 0, "king pressure is fully tapered out of pure endgames");
        check(Evaluator.gamePhase(pawnPressure) == 0, "pawn pressure test is pure endgame phase");
    }

    private static void testActivityColorSymmetry() {
        Board whiteAttack = board("7k/8/5N2/8/8/8/8/K7 w - - 0 1");
        Board blackAttack = board("k7/8/8/8/8/5n2/8/7K b - - 0 1");
        long whiteTerms = Evaluator.activityTerms(whiteAttack);
        long blackTerms = Evaluator.activityTerms(blackAttack);
        check((int) (whiteTerms >> 32) == -(int) (blackTerms >> 32),
                "middlegame activity is color-mirror symmetric");
        check((int) whiteTerms == -(int) blackTerms,
                "endgame activity is color-mirror symmetric");
        check(Evaluator.evaluate(whiteAttack) == Evaluator.evaluate(blackAttack),
                "mirrored activity has the same side-to-move score");
    }

    private static void testColorAndSideToMoveSymmetry() {
        Board white = board("7k/8/3P4/8/8/8/8/K7 w - - 0 1");
        Board black = board("k7/8/8/8/8/3p4/8/7K b - - 0 1");
        check(Evaluator.evaluate(white) == Evaluator.evaluate(black), "color-mirrored passer score");

        Board samePositionBlackToMove = board("7k/8/3P4/8/8/8/8/K7 b - - 0 1");
        check(Evaluator.evaluate(white) == -Evaluator.evaluate(samePositionBlackToMove),
                "side-to-move perspective");
    }

    private static Board board(String fen) {
        Board board = new Board();
        board.setFromFen(fen);
        return board;
    }

    private static int square(String name) {
        return Bitboards.squareFromName(name);
    }

    private static int evaluateWithoutActivity(Board board) {
        long terms = Evaluator.activityTerms(board);
        int phase = Evaluator.gamePhase(board);
        int activity = ((int) (terms >> 32) * phase
                + (int) terms * (Evaluator.PHASE_MAX - phase)) / Evaluator.PHASE_MAX;
        if (board.sideToMove == BLACK) activity = -activity;
        return Evaluator.evaluate(board) - activity;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

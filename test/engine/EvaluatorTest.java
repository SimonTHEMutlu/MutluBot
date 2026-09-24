package engine;

import static engine.Piece.BLACK;
import static engine.Piece.WHITE;

/** Focused regression tests for tapered evaluation terms. */
public final class EvaluatorTest {
    public static void main(String[] args) {
        testPassedPawnDetection();
        testPassedPawnValues();
        testPassedPawnScoreIsApplied();
        testEndgameKingCentralization();
        testOwnPassedPawnProximity();
        testEnemyPawnProximityAndPhaseTaper();
        testKingAttackUnitMapping();
        testSinglePieceKingPressure();
        testNonlinearKingPressure();
        testCentralQueenOuterRayIsIgnored();
        testOccupiedOuterZonePressureIsRetained();
        testQueenAbsenceReduction();
        testOpenAndSemiOpenKingFiles();
        testKingDangerCap();
        testKingDangerPhaseTaper();
        testIntactAndToleratedPawnShelter();
        testLooseAndMissingPawnShelter();
        testPawnShelterMonotonicity();
        testPawnShelterOffBackRankAndEdgeClamp();
        testPawnShelterColorAndSideSymmetry();
        testPawnShelterPhaseTaper();
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
            check(Evaluator.PASSED_PAWN_MG_BONUS[rank] > Evaluator.PASSED_PAWN_MG_BONUS[rank - 1], "middlegame passer rises");
            check(Evaluator.PASSED_PAWN_EG_BONUS[rank] > Evaluator.PASSED_PAWN_EG_BONUS[rank - 1], "endgame passer rises");
            check(Evaluator.PASSED_PAWN_EG_BONUS[rank] >= Evaluator.PASSED_PAWN_MG_BONUS[rank], "endgame passer is not lower");
        }
        check(Evaluator.PASSED_PAWN_MG_BONUS[6] == 80, "middlegame sixth-rank value");
        check(Evaluator.PASSED_PAWN_EG_BONUS[6] == 140, "endgame sixth-rank value");
    }

    private static void testPassedPawnScoreIsApplied() {
        Board blockedEndgame = board("7k/5p2/3p4/2P5/8/8/5P2/K7 w - - 0 1");
        Board passedEndgame = board("7k/5p2/4p3/2P5/8/8/5P2/K7 w - - 0 1");
        check(Evaluator.evaluate(passedEndgame) - Evaluator.evaluate(blockedEndgame) == 54, "endgame passer score is applied");
        Board blockedMiddlegame = board("nnbbrrqk/5p2/3p4/2P5/8/8/5P2/KQRRBBNN w - - 0 1");
        Board passedMiddlegame = board("nnbbrrqk/5p2/4p3/2P5/8/8/5P2/KQRRBBNN w - - 0 1");
        check(Evaluator.gamePhase(passedMiddlegame) == Evaluator.PHASE_MAX, "full middlegame phase");
        check(evaluateWithoutActivity(passedMiddlegame) - evaluateWithoutActivity(blockedMiddlegame) == 25, "middlegame passer score is applied");
    }

    private static void testEndgameKingCentralization() {
        Board corner = board("7k/8/8/8/8/8/8/K7 w - - 0 1");
        Board centerD4 = board("7k/8/8/8/3K4/8/8/8 w - - 0 1");
        Board centerD5 = board("7k/8/8/3K4/8/8/8/8 w - - 0 1");
        check(Evaluator.evaluate(centerD4) - Evaluator.evaluate(corner) == 60, "endgame center is worth 60 cp");
        check(Evaluator.evaluate(centerD4) == Evaluator.evaluate(centerD5), "endgame king table is vertically symmetric");
    }

    private static void testOwnPassedPawnProximity() {
        Board closer = board("7k/8/P7/8/3K4/8/8/8 w - - 0 1");
        Board farther = board("7k/8/P7/8/4K3/8/8/8 w - - 0 1");
        check(Evaluator.evaluate(closer) - Evaluator.evaluate(farther) == 4, "own passer proximity is applied");
    }

    private static void testEnemyPawnProximityAndPhaseTaper() {
        Board closer = board("7k/8/p7/8/3K4/8/8/8 w - - 0 1");
        Board farther = board("7k/8/p7/8/4K3/8/8/8 w - - 0 1");
        check(Evaluator.evaluate(closer) - Evaluator.evaluate(farther) == 2, "enemy pawn proximity is applied");
        Board fullCloser = board("nnbbrrqk/8/p7/8/3K4/8/8/1QRRBBNN w - - 0 1");
        Board fullFarther = board("nnbbrrqk/8/p7/8/4K3/8/8/1QRRBBNN w - - 0 1");
        check(evaluateWithoutActivity(fullCloser) == evaluateWithoutActivity(fullFarther), "enemy-pawn proximity tapers in middlegame");
    }

    private static void testSinglePieceKingPressure() {
        Board bishop = board("6k1/8/8/8/8/8/1B6/K7 w - - 0 1");
        Board rook = board("6k1/8/8/8/8/8/8/K4R2 w - - 0 1");
        Board queen = board("6k1/8/8/8/4Q3/8/8/K7 w - - 0 1");
        check(Evaluator.kingPressureMg(bishop, WHITE) > 0, "bishop pressure is nonzero");
        check(Evaluator.kingPressureMg(rook, WHITE) > 0, "rook pressure is nonzero");
        check(Evaluator.kingPressureMg(queen, WHITE) > 0, "queen pressure is nonzero");
        check(Evaluator.kingPressureMg(bishop, WHITE) < 25, "one attacker remains small");
    }

    private static void testKingAttackUnitMapping() {
        check(Evaluator.KING_ATTACK_UNIT[Piece.PAWN] == 0, "pawn uses its separate attack-unit path");
        check(Evaluator.KING_ATTACK_UNIT[Piece.KNIGHT] == 2, "knight king attack unit is two");
        check(Evaluator.KING_ATTACK_UNIT[Piece.BISHOP] == 2, "bishop king attack unit is two");
        check(Evaluator.KING_ATTACK_UNIT[Piece.ROOK] == 3, "rook king attack unit is three");
        check(Evaluator.KING_ATTACK_UNIT[Piece.QUEEN] == 5, "queen king attack unit is five");
        check(Evaluator.KING_ATTACK_UNIT[Piece.KING] == 0, "king has no king attack unit");
    }

    private static void testNonlinearKingPressure() {
        Board one = board("6k1/8/8/8/8/8/1B6/K7 w - - 0 1");
        Board two = board("6k1/8/7N/8/8/8/1B6/K7 w - - 0 1");
        check(Evaluator.kingPressureMg(two, WHITE) > Evaluator.kingPressureMg(one, WHITE) + 4, "coordinated attackers escalate nonlinearly");
    }

    private static void testCentralQueenOuterRayIsIgnored() {
        Board afterD4D5 = board("rnbqkbnr/ppp1pppp/8/3p4/3P4/8/PPP1PPPP/RNBQKBNR b KQkq - 0 2");
        Board afterQd3 = board("rnbqkbnr/ppp1pppp/8/3p4/3P4/3Q4/PPP1PPPP/RNB1KBNR b KQkq - 1 2");
        int before = (int) (Evaluator.activityTerms(afterD4D5) >> 32);
        int after = (int) (Evaluator.activityTerms(afterQd3) >> 32);
        check(after - before <= 1, "lone Qd3-to-empty-g6 outer ray does not earn king pressure");
    }

    private static void testOccupiedOuterZonePressureIsRetained() {
        Board occupied = board("6k1/8/5n2/8/8/8/1B6/K7 w - - 0 1");
        check(Evaluator.kingPressureMg(occupied, WHITE) > 0, "bishop pressure on occupied f6 remains positive");
    }

    private static void testQueenAbsenceReduction() {
        Board withQueen = board("6k1/8/8/8/8/8/1B2Q3/K7 w - - 0 1");
        Board noQueen = board("6k1/8/8/8/8/8/1B6/K7 w - - 0 1");
        check(Evaluator.kingPressureMg(withQueen, WHITE) > Evaluator.kingPressureMg(noQueen, WHITE), "queen presence raises danger");
    }

    private static void testOpenAndSemiOpenKingFiles() {
        Board open = board("6k1/8/8/8/8/8/8/K7 w - - 0 1");
        Board withRookOpen = board("r5k1/8/8/8/8/8/8/7K w - - 0 1");
        Board withRookSemiOpen = board("r5k1/8/8/8/8/8/6P1/7K w - - 0 1");
        Board withRookBlockedByAttackerPawn = board("r5k1/8/8/8/8/8/6p1/7K w - - 0 1");
        check(Evaluator.openFileDangerMg(withRookOpen, BLACK, WHITE)
                        > Evaluator.openFileDangerMg(withRookSemiOpen, BLACK, WHITE),
                "open file exceeds semi-open file danger with an enemy rook");
        check(Evaluator.openFileDangerMg(withRookSemiOpen, BLACK, WHITE)
                        > Evaluator.openFileDangerMg(withRookBlockedByAttackerPawn, BLACK, WHITE),
                "attacker pawn blocks its rook's semi-open-file danger");
        Board bishopOnly = board("6k1/8/8/8/8/8/8/b6K w - - 0 1");
        check(Evaluator.openFileDangerMg(open, BLACK, WHITE) == 0,
                "open files do not create danger without an attacking heavy piece");
        check(Evaluator.openFileDangerMg(bishopOnly, BLACK, WHITE) == 0,
                "a bishop alone does not create open-file danger");
    }

    private static void testKingDangerCap() {
        Board board = board("4NNQk/3RBR2/4N3/3Q1N2/8/8/8/K7 w - - 0 1");
        check(Evaluator.kingPressureMg(board, WHITE) <= Evaluator.KING_DANGER_MG_CAP, "king danger remains bounded");
    }

    private static void testKingDangerPhaseTaper() {
        Board ending = board("6k1/8/8/8/8/8/1B6/K7 w - - 0 1");
        Board full = board("nnbbrrqk/8/8/8/8/8/1B6/QRRBBNNK w - - 0 1");
        check(Evaluator.gamePhase(ending) == 1, "single bishop has residual phase");
        check(Evaluator.gamePhase(full) == Evaluator.PHASE_MAX, "full material reaches full phase");
        check(evaluateActivityContribution(ending) < Evaluator.kingPressureMg(ending, WHITE), "danger tapers in low phase");
        check(evaluateActivityContribution(full) != 0, "full-phase danger is applied");
    }

    private static void testIntactAndToleratedPawnShelter() {
        Board intact = board("k7/8/8/8/8/8/5PPP/6K1 w - - 0 1");
        Board oneLoose = board("k7/8/8/8/8/7P/5PP1/6K1 w - - 0 1");
        Board centralE4 = board("k7/8/8/8/4P3/8/5PPP/4K3 w - - 0 1");
        check(Evaluator.kingShelterPenaltyMg(intact, WHITE) == 0, "intact shelter has no penalty");
        check(Evaluator.kingShelterPenaltyMg(oneLoose, WHITE) == 0, "one-step loose pawn is tolerated");
        check(Evaluator.kingShelterPenaltyMg(centralE4, WHITE) == 0, "Ke1/e4 is not treated as castled shelter");
    }

    private static void testLooseAndMissingPawnShelter() {
        Board loose = board("k7/8/8/8/7P/6P1/5P2/6K1 w - - 0 1");
        Board missing = board("k7/8/8/8/8/8/5PP1/6K1 w - - 0 1");
        check(Evaluator.kingShelterPenaltyMg(loose, WHITE) == 14, "loose shelter penalty");
        check(Evaluator.kingShelterPenaltyMg(missing, WHITE) == 14, "missing shelter pawn penalty");
    }

    private static void testPawnShelterMonotonicity() {
        Board oneLoose = board("k7/8/8/8/8/7P/5PP1/6K1 w - - 0 1");
        Board twoLoose = board("k7/8/8/8/8/6PP/5P2/6K1 w - - 0 1");
        Board oneFar = board("k7/8/8/8/7P/8/5PP1/6K1 w - - 0 1");
        Board oneMissing = board("k7/8/8/8/8/8/5PP1/6K1 w - - 0 1");
        Board twoMissing = board("k7/8/8/8/8/8/5P2/6K1 w - - 0 1");
        Board threeMissing = board("k7/8/8/8/8/8/8/6K1 w - - 0 1");
        int p1 = Evaluator.kingShelterPenaltyMg(oneLoose, WHITE);
        int p2 = Evaluator.kingShelterPenaltyMg(twoLoose, WHITE);
        int p3 = Evaluator.kingShelterPenaltyMg(oneFar, WHITE);
        int p4 = Evaluator.kingShelterPenaltyMg(oneMissing, WHITE);
        int p5 = Evaluator.kingShelterPenaltyMg(twoMissing, WHITE);
        int p6 = Evaluator.kingShelterPenaltyMg(threeMissing, WHITE);
        check(p1 == 0 && p2 == 8 && p3 == 10 && p4 == 14 && p5 == 32 && p6 == 40, "shelter weights are documented");
        check(p1 < p2 && p2 < p3 && p3 < p4 && p4 < p5 && p5 < p6, "shelter is monotonic");
    }

    private static void testPawnShelterOffBackRankAndEdgeClamp() {
        Board advancedKing = board("k7/8/8/8/7P/5PP/6K1/8 w - - 0 1");
        Board edgeKing = board("7k/8/8/8/8/2P5/PP6/K7 w - - 0 1");
        check(Evaluator.kingShelterPenaltyMg(advancedKing, WHITE) == 0, "advanced king uses relative shelter");
        check(Evaluator.kingShelterPenaltyMg(edgeKing, WHITE) == 0, "edge shelter clamps files");
    }

    private static void testPawnShelterColorAndSideSymmetry() {
        Board whiteExposed = board("6k1/5ppp/8/8/7P/6P1/5P2/6K1 w - - 0 1");
        Board blackExposed = board("6k1/5p2/6p1/7p/8/8/5PPP/6K1 b - - 0 1");
        check(Evaluator.kingShelterPenaltyMg(whiteExposed, WHITE) == 14, "white exposed shelter");
        check(Evaluator.kingShelterPenaltyMg(blackExposed, BLACK) == 14, "black exposed shelter");
        check(Evaluator.evaluate(whiteExposed) == Evaluator.evaluate(blackExposed), "shelter mirror symmetry");
        Board blackToMove = board("6k1/5ppp/8/8/7P/6P1/5P2/6K1 b - - 0 1");
        check(Evaluator.evaluate(whiteExposed) == -Evaluator.evaluate(blackToMove), "shelter STM symmetry");
    }

    private static void testPawnShelterPhaseTaper() {
        Board pawnEnding = board("6k1/5ppp/8/8/7P/6P1/5P2/6K1 w - - 0 1");
        Board fullPhase = board("nnbbrrqk/5ppp/8/8/7P/6P1/5P2/QRRBBNKN w - - 0 1");
        check(Evaluator.gamePhase(pawnEnding) == 0, "pawn ending has zero phase");
        check(evaluateShelterContribution(pawnEnding) == 0, "shelter is absent in pawn ending");
        check(Evaluator.gamePhase(fullPhase) == Evaluator.PHASE_MAX, "full shelter phase");
        check(evaluateShelterContribution(fullPhase) == -14, "full shelter penalty is applied");
    }

    private static void testColorAndSideToMoveSymmetry() {
        Board white = board("7k/8/3P4/8/8/8/8/K7 w - - 0 1");
        Board black = board("k7/8/8/8/8/3p4/8/7K b - - 0 1");
        check(Evaluator.evaluate(white) == Evaluator.evaluate(black), "color-mirrored passer score");
        Board samePositionBlackToMove = board("7k/8/3P4/8/8/8/8/K7 b - - 0 1");
        check(Evaluator.evaluate(white) == -Evaluator.evaluate(samePositionBlackToMove), "side-to-move perspective");
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
        int phase = Evaluator.gamePhase(board);
        long terms = Evaluator.activityTerms(board);
        int activity = ((int) (terms >> 32) * phase + (int) terms * (Evaluator.PHASE_MAX - phase)) / Evaluator.PHASE_MAX;
        if (board.sideToMove == BLACK) activity = -activity;
        return Evaluator.evaluate(board) - activity;
    }

    private static int evaluateActivityContribution(Board board) {
        return Evaluator.evaluate(board) - evaluateWithoutActivity(board);
    }

    private static int evaluateShelterContribution(Board board) {
        int rawWhiteRelative = Evaluator.kingShelterPenaltyMg(board, BLACK) - Evaluator.kingShelterPenaltyMg(board, WHITE);
        int tapered = rawWhiteRelative * Evaluator.gamePhase(board) / Evaluator.PHASE_MAX;
        return board.sideToMove == WHITE ? tapered : -tapered;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

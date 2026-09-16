package engine;

import java.util.Arrays;

import static engine.Piece.*;
import static engine.Evaluator.*;

/**
 * Negamax alpha-beta search with:
 *   - iterative deepening
 *   - a transposition table
 *   - MVV-LVA capture ordering, killer moves, history heuristic
 *   - null-move pruning
 *   - a simple form of PVS (principal variation search) with late move reductions
 *   - quiescence search with SEE and delta pruning at leaf nodes
 *
 * This is intentionally a "solid but simple" search. Natural next steps to
 * strengthen it: aspiration windows, better LMR conditions, futility/razoring
 * pruning, static exchange evaluation (SEE) for capture ordering, a real
 * multi-ply PV table instead of reconstructing the PV from the TT, etc.
 */
public class Search {

    public static final int MAX_PLY = 128;

    private final TranspositionTable tt;
    private final int[][] killerMoves = new int[MAX_PLY][2];
    private final int[][] historyTable = new int[64][64];
    private final int[][] seeGains = new int[MAX_PLY][32];
    // Each recursion level owns reusable buffers so nodes do not create garbage.
    private final MoveList[] moveLists = new MoveList[MAX_PLY];
    private final int[][] moveScores = new int[MAX_PLY][256];
    private final boolean[] repetitionTainted = new boolean[MAX_PLY];
    private long[] keyStack;
    private int historyBase;

    private volatile boolean stopRequested;
    private long nodes;
    private long deadlineNanos;
    private boolean timeLimited;

    private int rootBestMove;
    private int iterationBestMove;
    private int selDepth;

    public interface InfoListener {
        void onInfo(int depth, int seldepth, int scoreCp, boolean isMate, int mateIn,
                    long nodes, long nps, long timeMs, String pv);
    }

    private InfoListener listener;

    public Search(TranspositionTable tt) {
        this.tt = tt;
        for (int ply = 0; ply < MAX_PLY; ply++) {
            moveLists[ply] = new MoveList();
        }
    }

    public void setInfoListener(InfoListener l) {
        this.listener = l;
    }

    public void requestStop() {
        stopRequested = true;
    }

    /**
     * Iterative deepening search from the current position on `board`.
     *
     * @param maxDepth       depth limit, or <= 0 for "no explicit depth limit"
     * @param timeMillis     time budget in ms, or < 0 for "no time limit" (rely on stop()/maxDepth)
     * @param gameHistoryKeys zobrist keys of positions played so far in the actual game
     *                        (oldest first), used for accurate repetition detection; may be null.
     *                        The current root may be present as the final entry.
     */
    public int search(Board board, int maxDepth, long timeMillis, long[] gameHistoryKeys) {
        stopRequested = false;
        nodes = 0;
        tt.newSearch();
        for (int[] k : killerMoves) Arrays.fill(k, Move.NONE);
        for (int[] row : historyTable) Arrays.fill(row, 0);

        int historyLength = gameHistoryKeys != null ? gameHistoryKeys.length : 0;
        boolean historyIncludesRoot = historyLength > 0
                && gameHistoryKeys[historyLength - 1] == board.zobristKey;
        historyBase = historyIncludesRoot ? historyLength - 1 : historyLength;
        keyStack = new long[historyBase + MAX_PLY + 8];
        if (gameHistoryKeys != null && historyBase > 0) {
            System.arraycopy(gameHistoryKeys, 0, keyStack, 0, historyBase);
        }

        timeLimited = timeMillis >= 0;
        long startNanos = System.nanoTime();
        deadlineNanos = timeLimited ? startNanos + timeMillis * 1_000_000L : Long.MAX_VALUE;

        int depthLimit = maxDepth > 0 ? maxDepth : MAX_PLY - 4;
        rootBestMove = Move.NONE;
        int bestScore = 0;
        int window = 25; // centipawns, tunable
        int alpha, beta;

        for (int depth = 1; depth <= depthLimit; depth++) {
            iterationBestMove = Move.NONE;
            if(depth <= 2 || Math.abs(bestScore) >= MATE_SCORE - MAX_PLY - 10)
            {
                alpha = -INFINITY_SCORE;
                beta = INFINITY_SCORE;
            }
            else{
                alpha = bestScore - window;
                beta = bestScore + window;
            }

            selDepth = depth;
            int score;
            while(true) {
                score = negamax(board, depth, alpha, beta, 0, true);
                if(score <= alpha){
                    alpha = -INFINITY_SCORE; // fail low - widen and retry at the same depth
                }  else if (score >= beta){
                    beta = INFINITY_SCORE; // fail high - widen and retry at the same depth
                }
                else{
                    break; // score landed inside the window - trustworthy, move on
                }
            }

            if (stopRequested && depth > 1) {
                break; // incomplete iteration; keep the previous depth's result
            }

            bestScore = score;
            if (iterationBestMove != Move.NONE) rootBestMove = iterationBestMove;

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            if (listener != null) reportInfo(board, depth, bestScore, elapsedMs);

            if (stopRequested) break;
            if (timeLimited) {
                long used = System.nanoTime() - startNanos;
                long budget = deadlineNanos - startNanos;
                if (used * 2 > budget) break; // unlikely to finish another iteration in time
            }
            if (Math.abs(bestScore) >= MATE_SCORE - MAX_PLY) break; // forced mate found
        }

        if (rootBestMove == Move.NONE) {
            MoveList legal = new MoveList();
            MoveGenerator.generateLegal(board, legal);
            if (legal.size > 0) rootBestMove = legal.get(0);
        }
        return rootBestMove;
    }

    private void reportInfo(Board board, int depth, int score, long elapsedMs) {
        long nps = elapsedMs > 0 ? nodes * 1000L / elapsedMs : nodes;
        boolean isMate = Math.abs(score) >= MATE_SCORE - MAX_PLY;
        int mateIn = 0;
        if (isMate) {
            int pliesToMate = MATE_SCORE - Math.abs(score);
            mateIn = (pliesToMate + 1) / 2;
            if (score < 0) mateIn = -mateIn;
        }
        String pv = extractPv(board, Math.max(depth, 1), iterationBestMove);
        listener.onInfo(depth, selDepth, score, isMate, mateIn, nodes, nps, elapsedMs, pv);
    }

    private String extractPv(Board board, int maxLen, int reportedRootMove) {
        StringBuilder sb = new StringBuilder();
        int madeMoves = 0;
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < maxLen; i++) {
            if (!seen.add(board.zobristKey)) break;
            int move;
            if (i == 0 && reportedRootMove != Move.NONE) {
                move = reportedRootMove;
            } else {
                TranspositionTable.Probe p = tt.probe(board.zobristKey);
                if (p == null || p.move == Move.NONE) break;
                move = p.move;
            }
            if (!isLegalInPosition(board, move)) break;
            board.makeMove(move);
            madeMoves++;
            sb.append(sb.length() == 0 ? "" : " ").append(Move.toUci(move));
        }
        for (int i = 0; i < madeMoves; i++) board.unmakeMove();
        return sb.toString();
    }

    private boolean isLegalInPosition(Board board, int move) {
        MoveList legal = new MoveList();
        MoveGenerator.generateLegal(board, legal);
        for (int i = 0; i < legal.size; i++) if (legal.get(i) == move) return true;
        return false;
    }

    private boolean checkTime() {
        return timeLimited && System.nanoTime() >= deadlineNanos;
    }

    private boolean hasNonPawnMaterial(Board b, int color) {
        return (b.pieceBB[color][KNIGHT] | b.pieceBB[color][BISHOP]
              | b.pieceBB[color][ROOK] | b.pieceBB[color][QUEEN]) != 0;
    }

    private int adjustMateToTT(int score, int searchPly) {
        if (score >= MATE_SCORE - MAX_PLY) return score + searchPly;
        if (score <= -(MATE_SCORE - MAX_PLY)) return score - searchPly;
        return score;
    }

    private int adjustMateFromTT(int score, int searchPly) {
        if (score >= MATE_SCORE - MAX_PLY) return score - searchPly;
        if (score <= -(MATE_SCORE - MAX_PLY)) return score + searchPly;
        return score;
    }

    private int negamax(Board board, int depth, int alpha, int beta, int searchPly, boolean nullOk) {
        if ((nodes & 2047) == 0 && checkTime()) stopRequested = true;
        if (stopRequested) return 0;
        nodes++;

        repetitionTainted[searchPly] = false;

        if (searchPly >= MAX_PLY - 1) return Evaluator.evaluate(board);

        int absPly = historyBase + searchPly;
        keyStack[absPly] = board.zobristKey;
        if (board.halfmoveClock >= 100) {
            boolean checked = board.isInCheck(board.sideToMove);
            if (!checked || MoveGenerator.hasLegalMove(board)) return 0;
            return -(MATE_SCORE - searchPly);
        }
        int repetitions = 0;
        for (int p = absPly - 2; p >= 0; p -= 2) {
            if (keyStack[p] == board.zobristKey) {
                repetitions++;
                repetitionTainted[searchPly] = true;
                if (repetitions >= 2) return 0; // this occurrence is the genuine 3rd
            }
        }
        boolean repetitionSensitive = repetitions > 0;

        boolean inCheck = board.isInCheck(board.sideToMove);
        if (depth <= 0) {
            if (inCheck) depth = 1; // don't enter quiescence while in check
            else return quiescence(board, alpha, beta, searchPly);
        }

        int origAlpha = alpha;
        int ttMove = Move.NONE;
        long entry = tt.probePacked(board.zobristKey);
        if (entry != 0) {
            ttMove = TranspositionTable.moveOf(entry);
            // A TT score is safe only inside the search that produced it, and
            // not when this position has already occurred on the actual-game
            // history or active line. The move remains useful for ordering.
            if (!repetitionSensitive && tt.isCurrentGeneration(entry)
                    && TranspositionTable.depthOf(entry) >= depth) {
                int score = adjustMateFromTT(TranspositionTable.scoreOf(entry), searchPly);
                int flag = TranspositionTable.flagOf(entry);
                if (flag == TranspositionTable.EXACT) {
                    if (searchPly == 0) iterationBestMove = ttMove;
                    return score;
                }
                if (flag == TranspositionTable.LOWER_BOUND && score > alpha) alpha = score;
                else if (flag == TranspositionTable.UPPER_BOUND && score < beta) beta = score;
                if (alpha >= beta) {
                    if (searchPly == 0) iterationBestMove = ttMove;
                    return score;
                }
            }
        }

        if (nullOk && !inCheck && depth >= 3 && searchPly > 0 && hasNonPawnMaterial(board, board.sideToMove)) {
            board.makeNullMove();
            int score = -negamax(board, depth - 3, -beta, -beta + 1, searchPly + 1, false);
            board.unmakeNullMove();
            if (repetitionTainted[searchPly + 1]) repetitionTainted[searchPly] = true;
            if (stopRequested) return 0;
            if (score >= beta) return beta;
        }

        MoveList moves = moveLists[searchPly];
        moves.clear();
        MoveGenerator.generatePseudoLegal(board, moves, false);
        int[] scores = scoreBuffer(searchPly, moves.size);
        scoreMoves(board, moves, ttMove, searchPly, scores);

        int legalCount = 0;
        int bestScore = -INFINITY_SCORE;
        int bestMove = Move.NONE;
        int us = board.sideToMove;

        for (int i = 0; i < moves.size; i++) {
            int bestIdx = i;
            for (int j = i + 1; j < moves.size; j++) if (scores[j] > scores[bestIdx]) bestIdx = j;
            if (bestIdx != i) {
                int tmpM = moves.moves[i]; moves.moves[i] = moves.moves[bestIdx]; moves.moves[bestIdx] = tmpM;
                int tmpS = scores[i]; scores[i] = scores[bestIdx]; scores[bestIdx] = tmpS;
            }
            int move = moves.get(i);

            board.makeMove(move);
            if (board.isInCheck(us)) {
                board.unmakeMove();
                continue;
            }
            legalCount++;

            int score;
            boolean childRepetitionTainted;
            if (legalCount == 1) {
                score = -negamax(board, depth - 1, -beta, -alpha, searchPly + 1, true);
                childRepetitionTainted = repetitionTainted[searchPly + 1];
            } else {
                boolean quiet = !Move.isCapture(move) && !Move.isPromotion(move);
                int reduction = (quiet && depth >= 3 && legalCount > 4) ? 1 : 0;
                score = -negamax(board, depth - 1 - reduction, -alpha - 1, -alpha, searchPly + 1, true);
                childRepetitionTainted = repetitionTainted[searchPly + 1];
                if (score > alpha) {
                    score = -negamax(board, depth - 1, -beta, -alpha, searchPly + 1, true);
                    childRepetitionTainted |= repetitionTainted[searchPly + 1];
                }
            }

            board.unmakeMove();
            if (childRepetitionTainted) repetitionTainted[searchPly] = true;
            if (stopRequested) return 0;

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
            if (score > alpha) {
                alpha = score;
            }
            if (alpha >= beta) {
                if (!Move.isCapture(move)) {
                    historyTable[Move.from(move)][Move.to(move)] += depth * depth;
                    if (killerMoves[searchPly][0] != move) {
                        killerMoves[searchPly][1] = killerMoves[searchPly][0];
                        killerMoves[searchPly][0] = move;
                    }
                }
                break;
            }
        }

        if (legalCount == 0) {
            return inCheck ? -(MATE_SCORE - searchPly) : 0;
        }

        int flag;
        if (bestScore <= origAlpha) flag = TranspositionTable.UPPER_BOUND;
        else if (bestScore >= beta) flag = TranspositionTable.LOWER_BOUND;
        else flag = TranspositionTable.EXACT;
        if (!repetitionTainted[searchPly]) {
            tt.store(board.zobristKey, depth, adjustMateToTT(bestScore, searchPly), flag, bestMove);
        }
        if (searchPly == 0) iterationBestMove = bestMove;

        return bestScore;
    }

    private int quiescence(Board board, int alpha, int beta, int searchPly) {
        if ((nodes & 2047) == 0 && checkTime()) stopRequested = true;
        if (stopRequested) return 0;
        nodes++;

        repetitionTainted[searchPly] = false;

        if (searchPly >= MAX_PLY - 1) return Evaluator.evaluate(board);

        if (keyStack != null) {
            int absPly = historyBase + searchPly;
            keyStack[absPly] = board.zobristKey;
            int repetitions = 0;
            for (int p = absPly - 2; p >= 0; p -= 2) {
                if (keyStack[p] == board.zobristKey) {
                    repetitions++;
                    repetitionTainted[searchPly] = true;
                    if (repetitions >= 2) return 0;
                }
            }
        }

        boolean inCheck = board.isInCheck(board.sideToMove);
        int standPat = -INFINITY_SCORE;
        if (!inCheck) {
            standPat = Evaluator.evaluate(board);
            if (standPat >= beta) return beta;
            if (standPat > alpha) alpha = standPat;
        }

        MoveList moves = moveLists[searchPly];
        moves.clear();
        MoveGenerator.generatePseudoLegal(board, moves, !inCheck);
        int[] scores = scoreBuffer(searchPly, moves.size);
        if (inCheck) scoreMoves(board, moves, Move.NONE, searchPly, scores);
        else scoreCaptures(board, moves, scores);

        int us = board.sideToMove;
        int legalCount = 0;
        for (int i = 0; i < moves.size; i++) {
            int bestIdx = i;
            for (int j = i + 1; j < moves.size; j++) if (scores[j] > scores[bestIdx]) bestIdx = j;
            if (bestIdx != i) {
                int tmpM = moves.moves[i]; moves.moves[i] = moves.moves[bestIdx]; moves.moves[bestIdx] = tmpM;
                int tmpS = scores[i]; scores[i] = scores[bestIdx]; scores[bestIdx] = tmpS;
            }
            int move = moves.get(i);

            // A capture that cannot lift alpha need not be searched unless it gives check.
            // Promotions, en passant, and king captures have tactical edge cases, so keep them.
            boolean prune = false;
            if (!inCheck && !Move.isPromotion(move) && !Move.isEnPassant(move)
                    && board.pieceTypeAt(Move.from(move)) != KING
                    && Math.abs(alpha) < MATE_SCORE - MAX_PLY) {
                int victim = board.pieceTypeAt(Move.to(move));
                prune = standPat + PIECE_VALUE[victim] + 200 < alpha;
                // A cheaper attacker cannot lose material on this square: the
                // opponent can take at most that attacker before we may stop.
                if (!prune && PIECE_VALUE[board.pieceTypeAt(Move.from(move))] > PIECE_VALUE[victim]) {
                    prune = StaticExchange.evaluate(board, move, seeGains[searchPly]) < 0;
                }
            }

            board.makeMove(move);
            if (board.isInCheck(us)) {
                board.unmakeMove();
                continue;
            }
            legalCount++;
            if (prune && !board.isInCheck(board.sideToMove)) {
                board.unmakeMove();
                continue;
            }
            int score = -quiescence(board, -beta, -alpha, searchPly + 1);
            board.unmakeMove();
            if (repetitionTainted[searchPly + 1]) repetitionTainted[searchPly] = true;
            if (stopRequested) return 0;

            if (score >= beta) return beta;
            if (score > alpha) alpha = score;
        }
        if (inCheck && legalCount == 0) return -(MATE_SCORE - searchPly);
        return alpha;
    }

    private int[] scoreBuffer(int searchPly, int requiredSize) {
        int[] scores = moveScores[searchPly];
        if (requiredSize > scores.length) {
            int newSize = scores.length;
            while (newSize < requiredSize) newSize *= 2;
            scores = Arrays.copyOf(scores, newSize);
            moveScores[searchPly] = scores;
        }
        return scores;
    }

    private void scoreMoves(Board board, MoveList moves, int ttMove,
                            int searchPly, int[] scores) {
        for (int i = 0; i < moves.size; i++) {
            int move = moves.get(i);
            if (move == ttMove) {
                scores[i] = 2_000_000;
            } else if (Move.isCapture(move)) {
                scores[i] = 1_000_000 + mvvLva(board, move);
            } else if (Move.isPromotion(move)) {
                scores[i] = 900_000 + PIECE_VALUE[Move.promotionPieceType(move)];
            } else if (move == killerMoves[searchPly][0]) {
                scores[i] = 800_000;
            } else if (move == killerMoves[searchPly][1]) {
                scores[i] = 790_000;
            } else {
                scores[i] = historyTable[Move.from(move)][Move.to(move)];
            }
        }
    }

    private void scoreCaptures(Board board, MoveList moves, int[] scores) {
        for (int i = 0; i < moves.size; i++) {
            scores[i] = mvvLva(board, moves.get(i));
        }
    }

    private int mvvLva(Board board, int move) {
        int to = Move.to(move);
        int victimType = Move.isEnPassant(move) ? PAWN : board.pieceTypeAt(to);
        int attackerType = board.pieceTypeAt(Move.from(move));
        int victimVal = victimType >= 0 ? PIECE_VALUE[victimType] : PAWN_VALUE;
        int attackerVal = attackerType >= 0 ? PIECE_VALUE[attackerType] : 0;
        int s = victimVal * 16 - attackerVal;
        if (Move.isPromotion(move)) s += PIECE_VALUE[Move.promotionPieceType(move)];
        return s;
    }
}

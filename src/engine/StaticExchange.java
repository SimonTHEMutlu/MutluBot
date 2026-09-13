package engine;

import static engine.Bitboards.*;
import static engine.Evaluator.PIECE_VALUE;
import static engine.Evaluator.PAWN_VALUE;
import static engine.Piece.*;

/** Material result of a capture followed by optimal recaptures on its destination. */
final class StaticExchange {
    private StaticExchange() {}

    /** The caller supplies a reusable 32-element buffer to avoid allocations in search. */
    static int evaluate(Board board, int move, int[] gains) {
        int from = Move.from(move);
        int to = Move.to(move);
        int us = board.sideToMove;
        int victim = Move.isEnPassant(move) ? PAWN : board.pieceTypeAt(to);
        int attacker = board.pieceTypeAt(from);
        int occupant = Move.isPromotion(move) ? Move.promotionPieceType(move) : attacker;
        int promotionGain = Move.isPromotion(move) ? PIECE_VALUE[occupant] - PAWN_VALUE : 0;

        gains[0] = PIECE_VALUE[victim] + promotionGain;
        int depth = 0;
        long toBit = 1L << to;
        long occupied = (board.allOccupancy & ~(1L << from)) | toBit;
        if (Move.isEnPassant(move)) {
            int capturedSquare = to + (us == WHITE ? -8 : 8);
            occupied &= ~(1L << capturedSquare);
        }

        int side = opposite(us);
        while (depth < gains.length - 1) {
            int attackerSquare = -1;
            int attackerType = -1;
            long attackers = attackersTo(board, to, occupied, side, toBit);
            for (int type = PAWN; type <= KING; type++) {
                long candidates = attackers & board.pieceBB[side][type];
                while (candidates != 0) {
                    int square = lsb(candidates);
                    candidates &= candidates - 1;
                    int kingSquare = type == KING ? to : board.kingSquare(side);
                    // Only a move along a king ray can uncover a pin. King
                    // recaptures still need a full attacked-square check.
                    if ((type != KING && !sharesLine(kingSquare, square))
                            || !isAttacked(board, kingSquare, opposite(side),
                                    occupied & ~(1L << square), toBit)) {
                        attackerSquare = square;
                        attackerType = type;
                        break;
                    }
                }
                if (attackerSquare >= 0) break;
            }
            if (attackerSquare < 0) break;

            gains[++depth] = PIECE_VALUE[occupant] - gains[depth - 1];
            occupied &= ~(1L << attackerSquare);
            occupant = attackerType;
            side = opposite(side);
            if (attackerType == KING) break;
        }

        while (depth > 0) {
            gains[depth - 1] = -Math.max(-gains[depth - 1], gains[depth]);
            depth--;
        }
        return gains[0];
    }

    private static long attackersTo(Board board, int square, long occupied, int side, long excluded) {
        long active = occupied & ~excluded;
        long attackers = PAWN_ATTACKS[opposite(side)][square] & board.pieceBB[side][PAWN];
        attackers |= KNIGHT_ATTACKS[square] & board.pieceBB[side][KNIGHT];
        attackers |= KING_ATTACKS[square] & board.pieceBB[side][KING];
        attackers |= bishopAttacks(square, occupied)
                & (board.pieceBB[side][BISHOP] | board.pieceBB[side][QUEEN]);
        attackers |= rookAttacks(square, occupied)
                & (board.pieceBB[side][ROOK] | board.pieceBB[side][QUEEN]);
        return attackers & active;
    }

    private static boolean isAttacked(Board board, int square, int bySide, long occupied, long excluded) {
        return attackersTo(board, square, occupied, bySide, excluded) != 0;
    }

    private static boolean sharesLine(int a, int b) {
        int fileDistance = Math.abs((a & 7) - (b & 7));
        int rankDistance = Math.abs((a >>> 3) - (b >>> 3));
        return fileDistance == 0 || rankDistance == 0 || fileDistance == rankDistance;
    }
}

package engine;

import static engine.Piece.*;
import static engine.Bitboards.*;

public class MoveGenerator {

    /**
     * Generates pseudo-legal moves (does not check whether the moving side's
     * own king is left in check). Use generateLegalMoves for fully legal moves.
     *
     * @param capturesOnly if true, only generates captures and promotions
     *                     (used by quiescence search); other quiet moves are skipped.
     */
    public static void generatePseudoLegal(Board b, MoveList list, boolean capturesOnly) {
        int us = b.sideToMove;
        int them = Piece.opposite(us);
        long own = b.occupancy[us];
        long enemy = b.occupancy[them];
        long all = b.allOccupancy;

        generatePawnMoves(b, list, us, them, capturesOnly);

        long knights = b.pieceBB[us][KNIGHT];
        while (knights != 0) {
            int from = lsb(knights);
            knights &= knights - 1;
            long targets = KNIGHT_ATTACKS[from] & ~own;
            emitTargets(list, from, targets, enemy, capturesOnly);
        }

        long bishops = b.pieceBB[us][BISHOP];
        while (bishops != 0) {
            int from = lsb(bishops);
            bishops &= bishops - 1;
            long targets = bishopAttacks(from, all) & ~own;
            emitTargets(list, from, targets, enemy, capturesOnly);
        }

        long rooks = b.pieceBB[us][ROOK];
        while (rooks != 0) {
            int from = lsb(rooks);
            rooks &= rooks - 1;
            long targets = rookAttacks(from, all) & ~own;
            emitTargets(list, from, targets, enemy, capturesOnly);
        }

        long queens = b.pieceBB[us][QUEEN];
        while (queens != 0) {
            int from = lsb(queens);
            queens &= queens - 1;
            long targets = queenAttacks(from, all) & ~own;
            emitTargets(list, from, targets, enemy, capturesOnly);
        }

        int kingSq = b.kingSquare(us);
        long kingTargets = KING_ATTACKS[kingSq] & ~own;
        emitTargets(list, kingSq, kingTargets, enemy, capturesOnly);

        if (!capturesOnly) {
            generateCastling(b, list, us);
        }
    }

    private static void emitTargets(MoveList list, int from, long targets, long enemy, boolean capturesOnly) {
        long captures = targets & enemy;
        while (captures != 0) {
            int to = lsb(captures);
            captures &= captures - 1;
            list.add(Move.encode(from, to, Move.CAPTURE));
        }
        if (!capturesOnly) {
            long quiets = targets & ~enemy;
            while (quiets != 0) {
                int to = lsb(quiets);
                quiets &= quiets - 1;
                list.add(Move.encode(from, to, Move.QUIET));
            }
        }
    }

    private static void generatePawnMoves(Board b, MoveList list, int us, int them, boolean capturesOnly) {
        long pawns = b.pieceBB[us][PAWN];
        long all = b.allOccupancy;
        long enemy = b.occupancy[them];
        int forward = us == WHITE ? 8 : -8;
        long startRank = us == WHITE ? RANK_2 : RANK_7;
        long promoRank = us == WHITE ? RANK_8 : RANK_1;

        long p = pawns;
        while (p != 0) {
            int from = lsb(p);
            p &= p - 1;
            int to1 = from + forward;

            if (to1 >= 0 && to1 < 64 && ((all >>> to1) & 1L) == 0) {
                if (((1L << to1) & promoRank) != 0) {
                    addPromotions(list, from, to1, false);
                } else if (!capturesOnly) {
                    list.add(Move.encode(from, to1, Move.QUIET));
                    if (((1L << from) & startRank) != 0) {
                        int to2 = from + 2 * forward;
                        if (((all >>> to2) & 1L) == 0) {
                            list.add(Move.encode(from, to2, Move.DOUBLE_PAWN_PUSH));
                        }
                    }
                }
            }

            long attacks = PAWN_ATTACKS[us][from] & enemy;
            while (attacks != 0) {
                int to = lsb(attacks);
                attacks &= attacks - 1;
                if (((1L << to) & promoRank) != 0) {
                    addPromotions(list, from, to, true);
                } else {
                    list.add(Move.encode(from, to, Move.CAPTURE));
                }
            }

            if (b.epSquare != -1) {
                long epBit = 1L << b.epSquare;
                if ((PAWN_ATTACKS[us][from] & epBit) != 0) {
                    list.add(Move.encode(from, b.epSquare, Move.EP_CAPTURE));
                }
            }
        }
    }

    private static void addPromotions(MoveList list, int from, int to, boolean capture) {
        if (capture) {
            list.add(Move.encode(from, to, Move.KNIGHT_PROMO_CAPTURE));
            list.add(Move.encode(from, to, Move.BISHOP_PROMO_CAPTURE));
            list.add(Move.encode(from, to, Move.ROOK_PROMO_CAPTURE));
            list.add(Move.encode(from, to, Move.QUEEN_PROMO_CAPTURE));
        } else {
            list.add(Move.encode(from, to, Move.KNIGHT_PROMO));
            list.add(Move.encode(from, to, Move.BISHOP_PROMO));
            list.add(Move.encode(from, to, Move.ROOK_PROMO));
            list.add(Move.encode(from, to, Move.QUEEN_PROMO));
        }
    }

    private static void generateCastling(Board b, MoveList list, int us) {
        long all = b.allOccupancy;
        int them = Piece.opposite(us);
        if (us == WHITE) {
            if ((b.castlingRights & Board.CASTLE_WK) != 0
                    && ((all >>> 5) & 1L) == 0 && ((all >>> 6) & 1L) == 0
                    && !b.isSquareAttacked(4, them) && !b.isSquareAttacked(5, them) && !b.isSquareAttacked(6, them)) {
                list.add(Move.encode(4, 6, Move.KING_CASTLE));
            }
            if ((b.castlingRights & Board.CASTLE_WQ) != 0
                    && ((all >>> 1) & 1L) == 0 && ((all >>> 2) & 1L) == 0 && ((all >>> 3) & 1L) == 0
                    && !b.isSquareAttacked(4, them) && !b.isSquareAttacked(3, them) && !b.isSquareAttacked(2, them)) {
                list.add(Move.encode(4, 2, Move.QUEEN_CASTLE));
            }
        } else {
            if ((b.castlingRights & Board.CASTLE_BK) != 0
                    && ((all >>> 61) & 1L) == 0 && ((all >>> 62) & 1L) == 0
                    && !b.isSquareAttacked(60, them) && !b.isSquareAttacked(61, them) && !b.isSquareAttacked(62, them)) {
                list.add(Move.encode(60, 62, Move.KING_CASTLE));
            }
            if ((b.castlingRights & Board.CASTLE_BQ) != 0
                    && ((all >>> 57) & 1L) == 0 && ((all >>> 58) & 1L) == 0 && ((all >>> 59) & 1L) == 0
                    && !b.isSquareAttacked(60, them) && !b.isSquareAttacked(59, them) && !b.isSquareAttacked(58, them)) {
                list.add(Move.encode(60, 58, Move.QUEEN_CASTLE));
            }
        }
    }

    /** Generates only fully legal moves by making/unmaking each pseudo-legal move. */
    public static void generateLegal(Board b, MoveList outList) {
        MoveList pseudo = new MoveList();
        generatePseudoLegal(b, pseudo, false);
        int us = b.sideToMove;
        for (int i = 0; i < pseudo.size; i++) {
            int move = pseudo.get(i);
            b.makeMove(move);
            if (!b.isInCheck(us)) {
                outList.add(move);
            }
            b.unmakeMove();
        }
    }

    public static boolean hasLegalMove(Board b) {
        MoveList pseudo = new MoveList();
        generatePseudoLegal(b, pseudo, false);
        int us = b.sideToMove;
        for (int i = 0; i < pseudo.size; i++) {
            int move = pseudo.get(i);
            b.makeMove(move);
            boolean stillLegal = !b.isInCheck(us);
            b.unmakeMove();
            if (stillLegal) return true;
        }
        return false;
    }
}

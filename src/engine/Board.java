package engine;

import java.util.ArrayDeque;

import static engine.Piece.*;
import static engine.Bitboards.*;

public class Board {

    public static final int CASTLE_WK = 1, CASTLE_WQ = 2, CASTLE_BK = 4, CASTLE_BQ = 8;

    public long[][] pieceBB = new long[2][6]; // [color][type]
    public long[] occupancy = new long[2];
    public long allOccupancy;

    public int sideToMove;
    public int castlingRights;
    public int epSquare; // -1 if none
    public int halfmoveClock;
    public int fullmoveNumber;
    public long zobristKey;

    public int[] mailbox = new int[64]; // encoded as color*6+type, or -1 if empty

    private static final int EMPTY = -1;

    private static class Undo {
        int move;
        int capturedPieceCode; // -1 if none
        int castlingRights;
        int epSquare;
        int halfmoveClock;
        long zobristKey;
    }

    private final ArrayDeque<Undo> history = new ArrayDeque<>();

    public Board() {
        setStartPosition();
    }

    public void setStartPosition() {
        setFromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    }

    public void setFromFen(String fen) {
        for (int c = 0; c < 2; c++)
            for (int t = 0; t < 6; t++)
                pieceBB[c][t] = 0L;
        for (int i = 0; i < 64; i++) mailbox[i] = EMPTY;
        history.clear();

        String[] parts = fen.trim().split("\\s+");
        String boardPart = parts[0];
        int rank = 7, file = 0;
        for (int i = 0; i < boardPart.length(); i++) {
            char ch = boardPart.charAt(i);
            if (ch == '/') {
                rank--;
                file = 0;
            } else if (Character.isDigit(ch)) {
                file += ch - '0';
            } else {
                int color = Character.isUpperCase(ch) ? WHITE : BLACK;
                int type = typeFromChar(Character.toLowerCase(ch));
                int sq = rank * 8 + file;
                pieceBB[color][type] |= 1L << sq;
                mailbox[sq] = color * 6 + type;
                file++;
            }
        }

        sideToMove = (parts.length > 1 && parts[1].equals("b")) ? BLACK : WHITE;

        castlingRights = 0;
        if (parts.length > 2) {
            String cr = parts[2];
            if (cr.indexOf('K') >= 0) castlingRights |= CASTLE_WK;
            if (cr.indexOf('Q') >= 0) castlingRights |= CASTLE_WQ;
            if (cr.indexOf('k') >= 0) castlingRights |= CASTLE_BK;
            if (cr.indexOf('q') >= 0) castlingRights |= CASTLE_BQ;
        }

        epSquare = -1;
        if (parts.length > 3 && !parts[3].equals("-")) {
            epSquare = squareFromName(parts[3]);
        }

        halfmoveClock = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
        fullmoveNumber = parts.length > 5 ? Integer.parseInt(parts[5]) : 1;

        recomputeOccupancy();
        zobristKey = computeZobristFromScratch();
    }

    private int typeFromChar(char c) {
        switch (c) {
            case 'p': return PAWN;
            case 'n': return KNIGHT;
            case 'b': return BISHOP;
            case 'r': return ROOK;
            case 'q': return QUEEN;
            case 'k': return KING;
        }
        throw new IllegalArgumentException("Bad piece char: " + c);
    }

    public void recomputeOccupancy() {
        occupancy[WHITE] = 0L;
        occupancy[BLACK] = 0L;
        for (int t = 0; t < 6; t++) {
            occupancy[WHITE] |= pieceBB[WHITE][t];
            occupancy[BLACK] |= pieceBB[BLACK][t];
        }
        allOccupancy = occupancy[WHITE] | occupancy[BLACK];
    }

    private long computeZobristFromScratch() {
        long key = 0L;
        for (int c = 0; c < 2; c++) {
            for (int t = 0; t < 6; t++) {
                long bb = pieceBB[c][t];
                while (bb != 0) {
                    int sq = lsb(bb);
                    bb &= bb - 1;
                    key ^= Zobrist.PIECE_KEY[c][t][sq];
                }
            }
        }
        if ((castlingRights & CASTLE_WK) != 0) key ^= Zobrist.CASTLE_KEY[0];
        if ((castlingRights & CASTLE_WQ) != 0) key ^= Zobrist.CASTLE_KEY[1];
        if ((castlingRights & CASTLE_BK) != 0) key ^= Zobrist.CASTLE_KEY[2];
        if ((castlingRights & CASTLE_BQ) != 0) key ^= Zobrist.CASTLE_KEY[3];
        if (epSquare != -1) key ^= Zobrist.EP_FILE_KEY[epSquare & 7];
        if (sideToMove == BLACK) key ^= Zobrist.SIDE_KEY;
        return key;
    }

    public String toFen() {
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int emptyRun = 0;
            for (int file = 0; file < 8; file++) {
                int sq = rank * 8 + file;
                int code = mailbox[sq];
                if (code == EMPTY) {
                    emptyRun++;
                } else {
                    if (emptyRun > 0) {
                        sb.append(emptyRun);
                        emptyRun = 0;
                    }
                    int color = code / 6, type = code % 6;
                    sb.append(Piece.toChar(color, type));
                }
            }
            if (emptyRun > 0) sb.append(emptyRun);
            if (rank > 0) sb.append('/');
        }
        sb.append(' ').append(sideToMove == WHITE ? 'w' : 'b').append(' ');
        StringBuilder cr = new StringBuilder();
        if ((castlingRights & CASTLE_WK) != 0) cr.append('K');
        if ((castlingRights & CASTLE_WQ) != 0) cr.append('Q');
        if ((castlingRights & CASTLE_BK) != 0) cr.append('k');
        if ((castlingRights & CASTLE_BQ) != 0) cr.append('q');
        sb.append(cr.length() == 0 ? "-" : cr.toString()).append(' ');
        sb.append(epSquare == -1 ? "-" : squareName(epSquare)).append(' ');
        sb.append(halfmoveClock).append(' ').append(fullmoveNumber);
        return sb.toString();
    }

    public int pieceTypeAt(int sq) {
        int code = mailbox[sq];
        return code == EMPTY ? -1 : code % 6;
    }

    public int pieceColorAt(int sq) {
        int code = mailbox[sq];
        return code == EMPTY ? -1 : code / 6;
    }

    public int kingSquare(int color) {
        return lsb(pieceBB[color][KING]);
    }

    /** True if 'sq' is attacked by any piece of color 'bySide', given current board occupancy. */
    public boolean isSquareAttacked(int sq, int bySide) {
        if ((KNIGHT_ATTACKS[sq] & pieceBB[bySide][KNIGHT]) != 0) return true;
        if ((KING_ATTACKS[sq] & pieceBB[bySide][KING]) != 0) return true;
        // Pawn: use opposite color's pawn-attack pattern from sq to find attacking pawns
        if ((PAWN_ATTACKS[opposite(bySide)][sq] & pieceBB[bySide][PAWN]) != 0) return true;
        long rq = pieceBB[bySide][ROOK] | pieceBB[bySide][QUEEN];
        if (rq != 0 && (rookAttacks(sq, allOccupancy) & rq) != 0) return true;
        long bq = pieceBB[bySide][BISHOP] | pieceBB[bySide][QUEEN];
        if (bq != 0 && (bishopAttacks(sq, allOccupancy) & bq) != 0) return true;
        return false;
    }

    public boolean isInCheck(int color) {
        return isSquareAttacked(kingSquare(color), opposite(color));
    }

    private void removePiece(int color, int type, int sq) {
        pieceBB[color][type] &= ~(1L << sq);
        occupancy[color] &= ~(1L << sq);
        allOccupancy &= ~(1L << sq);
        mailbox[sq] = EMPTY;
        zobristKey ^= Zobrist.PIECE_KEY[color][type][sq];
    }

    private void placePiece(int color, int type, int sq) {
        pieceBB[color][type] |= 1L << sq;
        occupancy[color] |= 1L << sq;
        allOccupancy |= 1L << sq;
        mailbox[sq] = color * 6 + type;
        zobristKey ^= Zobrist.PIECE_KEY[color][type][sq];
    }

    private void movePieceQuiet(int color, int type, int from, int to) {
        removePiece(color, type, from);
        placePiece(color, type, to);
    }

    public void makeMove(int move) {
        Undo u = new Undo();
        u.move = move;
        u.castlingRights = castlingRights;
        u.epSquare = epSquare;
        u.halfmoveClock = halfmoveClock;
        u.zobristKey = zobristKey;
        u.capturedPieceCode = EMPTY;

        int from = Move.from(move);
        int to = Move.to(move);
        int flag = Move.flag(move);
        int us = sideToMove, them = opposite(us);
        int movingType = pieceTypeAt(from);

        int oldEp = epSquare;
        int newEp = -1;

        // remove castling rights / halfmove clock bookkeeping done below
        int newCastling = castlingRights;

        if (flag == Move.EP_CAPTURE) {
            int capSq = to + (us == WHITE ? -8 : 8);
            u.capturedPieceCode = them * 6 + PAWN;
            removePiece(them, PAWN, capSq);
            movePieceQuiet(us, PAWN, from, to);
        } else if (flag == Move.KING_CASTLE) {
            movePieceQuiet(us, KING, from, to);
            if (us == WHITE) movePieceQuiet(WHITE, ROOK, 7, 5);
            else movePieceQuiet(BLACK, ROOK, 63, 61);
        } else if (flag == Move.QUEEN_CASTLE) {
            movePieceQuiet(us, KING, from, to);
            if (us == WHITE) movePieceQuiet(WHITE, ROOK, 0, 3);
            else movePieceQuiet(BLACK, ROOK, 56, 59);
        } else {
            boolean isCapture = Move.isCapture(move);
            if (isCapture) {
                int capturedType = pieceTypeAt(to);
                u.capturedPieceCode = them * 6 + capturedType;
                removePiece(them, capturedType, to);
            }
            removePiece(us, movingType, from);
            if (Move.isPromotion(move)) {
                placePiece(us, Move.promotionPieceType(move), to);
            } else {
                placePiece(us, movingType, to);
            }
        }

        // Update castling rights if king or rook moved/captured, or rook square touched
        if (movingType == KING) {
            if (us == WHITE) newCastling &= ~(CASTLE_WK | CASTLE_WQ);
            else newCastling &= ~(CASTLE_BK | CASTLE_BQ);
        }
        newCastling = clearCastlingRightsForSquare(newCastling, from);
        newCastling = clearCastlingRightsForSquare(newCastling, to);

        // En-passant target square
        if (flag == Move.DOUBLE_PAWN_PUSH) {
            newEp = us == WHITE ? from + 8 : from - 8;
        }

        // Zobrist: castling + ep + side
        for (int bit = 0; bit < 4; bit++) {
            int mask = 1 << bit;
            if ((castlingRights & mask) != (newCastling & mask)) {
                zobristKey ^= Zobrist.CASTLE_KEY[bit];
            }
        }
        if (oldEp != -1) zobristKey ^= Zobrist.EP_FILE_KEY[oldEp & 7];
        if (newEp != -1) zobristKey ^= Zobrist.EP_FILE_KEY[newEp & 7];
        zobristKey ^= Zobrist.SIDE_KEY;

        castlingRights = newCastling;
        epSquare = newEp;

        if (movingType == PAWN || Move.isCapture(move)) halfmoveClock = 0;
        else halfmoveClock++;

        if (us == BLACK) fullmoveNumber++;

        sideToMove = them;
        history.push(u);
    }

    private int clearCastlingRightsForSquare(int rights, int sq) {
        if (sq == 0) rights &= ~CASTLE_WQ;
        else if (sq == 7) rights &= ~CASTLE_WK;
        else if (sq == 56) rights &= ~CASTLE_BQ;
        else if (sq == 63) rights &= ~CASTLE_BK;
        return rights;
    }

    public void unmakeMove() {
        Undo u = history.pop();
        int move = u.move;
        int from = Move.from(move);
        int to = Move.to(move);
        int flag = Move.flag(move);

        sideToMove = opposite(sideToMove);
        int us = sideToMove, them = opposite(us);

        if (flag == Move.KING_CASTLE) {
            movePieceQuietNoHash(us, KING, to, from);
            if (us == WHITE) movePieceQuietNoHash(WHITE, ROOK, 5, 7);
            else movePieceQuietNoHash(BLACK, ROOK, 61, 63);
        } else if (flag == Move.QUEEN_CASTLE) {
            movePieceQuietNoHash(us, KING, to, from);
            if (us == WHITE) movePieceQuietNoHash(WHITE, ROOK, 3, 0);
            else movePieceQuietNoHash(BLACK, ROOK, 59, 56);
        } else if (flag == Move.EP_CAPTURE) {
            int movingType = PAWN;
            removePieceNoHash(us, movingType, to);
            placePieceNoHash(us, movingType, from);
            int capSq = to + (us == WHITE ? -8 : 8);
            placePieceNoHash(them, PAWN, capSq);
        } else {
            int placedType = Move.isPromotion(move) ? Move.promotionPieceType(move) : pieceTypeAt(to);
            removePieceNoHash(us, placedType, to);
            int originalType = Move.isPromotion(move) ? PAWN : placedType;
            placePieceNoHash(us, originalType, from);
            if (u.capturedPieceCode != EMPTY) {
                int capColor = u.capturedPieceCode / 6;
                int capType = u.capturedPieceCode % 6;
                placePieceNoHash(capColor, capType, to);
            }
        }

        castlingRights = u.castlingRights;
        epSquare = u.epSquare;
        halfmoveClock = u.halfmoveClock;
        zobristKey = u.zobristKey;
        if (us == BLACK) fullmoveNumber--;
    }

    // Variants that don't touch zobristKey (restored wholesale from Undo on unmake)
    private void removePieceNoHash(int color, int type, int sq) {
        pieceBB[color][type] &= ~(1L << sq);
        occupancy[color] &= ~(1L << sq);
        allOccupancy &= ~(1L << sq);
        mailbox[sq] = EMPTY;
    }

    private void placePieceNoHash(int color, int type, int sq) {
        pieceBB[color][type] |= 1L << sq;
        occupancy[color] |= 1L << sq;
        allOccupancy |= 1L << sq;
        mailbox[sq] = color * 6 + type;
    }

    private void movePieceQuietNoHash(int color, int type, int from, int to) {
        removePieceNoHash(color, type, from);
        placePieceNoHash(color, type, to);
    }

    public void makeNullMove() {
        Undo u = new Undo();
        u.move = -1;
        u.castlingRights = castlingRights;
        u.epSquare = epSquare;
        u.halfmoveClock = halfmoveClock;
        u.zobristKey = zobristKey;
        u.capturedPieceCode = EMPTY;
        history.push(u);

        if (epSquare != -1) zobristKey ^= Zobrist.EP_FILE_KEY[epSquare & 7];
        epSquare = -1;
        zobristKey ^= Zobrist.SIDE_KEY;
        sideToMove = opposite(sideToMove);
    }

    public void unmakeNullMove() {
        Undo u = history.pop();
        sideToMove = opposite(sideToMove);
        castlingRights = u.castlingRights;
        epSquare = u.epSquare;
        halfmoveClock = u.halfmoveClock;
        zobristKey = u.zobristKey;
    }

    public void printBoard() {
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            sb.append(rank + 1).append("  ");
            for (int file = 0; file < 8; file++) {
                int sq = rank * 8 + file;
                int code = mailbox[sq];
                sb.append(code == EMPTY ? '.' : Piece.toChar(code / 6, code % 6));
                sb.append(' ');
            }
            sb.append('\n');
        }
        sb.append("   a b c d e f g h\n");
        System.out.println(sb);
    }
}

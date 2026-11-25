package engine;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** MutluBot's own small, dual-perspective integer network. No external nets. */
public final class NnueNetwork {
    static final int QA = 255, QB = 256, FEATURES = 768;
    static final int CRELU = 0, SCRELU = 1;
    final int hidden, activation, version, inputBuckets;
    final int[] bias;
    final short[] weights, output;
    final long outputBias;
    final String sha256;

    private NnueNetwork(int hidden, int activation, int version, int inputBuckets, int[] bias,
                        short[] weights, short[] output, long outputBias, String sha256) {
        this.hidden = hidden;
        this.activation = activation;
        this.version = version;
        this.inputBuckets = inputBuckets;
        this.bias = bias;
        this.weights = weights;
        this.output = output;
        this.outputBias = outputBias;
        this.sha256 = sha256;
    }

    public static NnueNetwork load(String file) throws IOException {
        java.security.MessageDigest digest;
        try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IOException(error); }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new java.security.DigestInputStream(new java.io.FileInputStream(file), digest)))) {
            byte[] magic = new byte[8];
            in.readFully(magic);
            String format = new String(magic, StandardCharsets.US_ASCII);
            int version;
            if (format.equals("MUTLUN01")) version = 1;
            else if (format.equals("MUTLUN02")) version = 2;
            else if (format.equals("MUTLUN03")) version = 3;
            else throw new IOException("Not a supported MutluBot NNUE file");
            int hidden = in.readInt();
            if (hidden < 1 || hidden > 512 || in.readInt() != QA
                    || in.readInt() != QB || in.readInt() != FEATURES)
                throw new IOException("Unsupported NNUE dimensions/scales");
            int activation = version == 1 ? CRELU : in.readInt();
            if (activation != CRELU && activation != SCRELU)
                throw new IOException("Unsupported NNUE activation");
            int inputBuckets = 1;
            if (version >= 2) {
                inputBuckets = in.readInt();
                if (inputBuckets != (version == 3 ? 4 : 1) || in.readInt() != 1)
                    throw new IOException("Unsupported NNUE input/output bucket count");
                if (version == 3 && in.readInt() != 1)
                    throw new IOException("Unsupported NNUE king bucket map");
            }
            int[] bias = new int[hidden];
            short[] weights = new short[inputBuckets * FEATURES * hidden];
            short[] output = new short[2 * hidden];
            for (int i = 0; i < hidden; i++) {
                bias[i] = in.readInt();
                if (Math.abs((long) bias[i]) > 1000000L)
                    throw new IOException("NNUE bias out of range");
            }
            for (int i = 0; i < weights.length; i++) weights[i] = in.readShort();
            for (int i = 0; i < output.length; i++) output[i] = in.readShort();
            long outputBias = in.readLong();
            if (outputBias < -1000000000L || outputBias > 1000000000L)
                throw new IOException("NNUE output bias out of range");
            if (in.read() != -1) throw new IOException("Trailing NNUE data");
            StringBuilder hash = new StringBuilder(64);
            for (byte value : digest.digest()) hash.append(String.format("%02x", value & 255));
            return new NnueNetwork(hidden, activation, version, inputBuckets, bias, weights, output, outputBias, hash.toString());
        } catch (EOFException truncated) {
            throw new IOException("Truncated MutluBot NNUE file", truncated);
        }
    }

    static int feature(int perspective, int color, int type, int square) {
        return (((color ^ perspective) * 6 + type) * 64)
                + (perspective == Piece.WHITE ? square : square ^ 56);
    }

    // v3 map id 1. Input square is already oriented for the owner's perspective.
    static int kingBucket(int square) {
        if (square < 0 || square >= 64) throw new IllegalArgumentException("Missing NNUE king");
        if (square >= 16) return 3;
        int file = square & 7;
        return file <= 2 ? 0 : file <= 4 ? 1 : 2;
    }

    /** One mutable accumulator per board; updates/undo allocate no objects. */
    static final class State {
        final NnueNetwork network;
        final Board board;
        final int[][] accumulator;
        final int[] buckets = new int[2];
        // Legality generation makes/unmakes many moves without evaluating them.
        // Cancel those changes before touching wide vectors. The accumulator
        // represents the last evaluated board plus this bounded feature delta.
        final int[] delta = new int[FEATURES];
        final int[] touched = new int[FEATURES];
        final boolean[] marked = new boolean[FEATURES];
        int touchedCount;

        State(NnueNetwork network, Board board) {
            this.network = network;
            this.board = board;
            accumulator = new int[2][network.hidden];
            refresh(board);
        }

        void refresh(Board board) {
            java.util.Arrays.fill(delta, 0);
            java.util.Arrays.fill(marked, false);
            touchedCount = 0;
            for (int p = 0; p < 2; p++) rebuild(p, currentBucket(p));
        }

        private int currentBucket(int perspective) {
            if (network.inputBuckets == 1) return 0;
            int king = board.kingSquare(perspective);
            return kingBucket(perspective == Piece.WHITE ? king : king ^ 56);
        }

        private void rebuild(int perspective, int bucket) {
            int n = network.hidden;
            int[] acc = accumulator[perspective];
            System.arraycopy(network.bias, 0, acc, 0, n);
            for (int sq = 0; sq < 64; sq++) {
                int code = board.mailbox[sq];
                if (code < 0) continue;
                int offset = (bucket * FEATURES + feature(perspective, code / 6, code % 6, sq)) * n;
                for (int j = 0; j < n; j++) acc[j] += network.weights[offset + j];
            }
            buckets[perspective] = bucket;
        }

        void change(int color, int type, int square, int direction) {
            int index = (color * 6 + type) * 64 + square;
            if (!marked[index]) {
                marked[index] = true;
                touched[touchedCount++] = index;
            }
            delta[index] += direction;
        }

        void move(int color, int type, int from, int to) {
            change(color, type, from, -1);
            change(color, type, to, 1);
        }

        void flush() {
            int n = network.hidden;
            boolean rebuiltWhite = false, rebuiltBlack = false;
            if (network.inputBuckets != 1) {
                int white = currentBucket(Piece.WHITE), black = currentBucket(Piece.BLACK);
                if (buckets[Piece.WHITE] != white) {
                    rebuild(Piece.WHITE, white); rebuiltWhite = true;
                }
                if (buckets[Piece.BLACK] != black) {
                    rebuild(Piece.BLACK, black); rebuiltBlack = true;
                }
            }
            for (int k = 0; k < touchedCount; k++) {
                int index = touched[k], direction = delta[index];
                if (direction != 0) {
                    int color = index / 384, type = index / 64 % 6, square = index % 64;
                    for (int p = 0; p < 2; p++) {
                        // A rebuilt perspective already includes every pending delta.
                        if (p == Piece.WHITE ? rebuiltWhite : rebuiltBlack) continue;
                        int offset = (buckets[p] * FEATURES + feature(p, color, type, square)) * n;
                        int[] acc = accumulator[p];
                        if (direction == 1) {
                            for (int j = 0; j < n; j++) acc[j] += network.weights[offset + j];
                        } else if (direction == -1) {
                            for (int j = 0; j < n; j++) acc[j] -= network.weights[offset + j];
                        } else {
                            for (int j = 0; j < n; j++) acc[j] += direction * network.weights[offset + j];
                        }
                    }
                }
                delta[index] = 0;
                marked[index] = false;
            }
            touchedCount = 0;
        }

        int evaluate(int sideToMove) {
            flush();
            int n = network.hidden;
            int[] us = accumulator[sideToMove], them = accumulator[sideToMove ^ 1];
            long raw;
            long denominator;
            if (network.activation == SCRELU) {
                raw = network.outputBias * QA;
                for (int i = 0; i < n; i++) {
                    int a = Math.max(0, Math.min(QA, us[i]));
                    int b = Math.max(0, Math.min(QA, them[i]));
                    raw += (long) a * a * network.output[i]
                            + (long) b * b * network.output[n + i];
                }
                denominator = (long) QA * QA * QB;
            } else {
                raw = network.outputBias;
                for (int i = 0; i < n; i++) {
                    raw += (long) Math.max(0, Math.min(QA, us[i])) * network.output[i]
                            + (long) Math.max(0, Math.min(QA, them[i])) * network.output[n + i];
                }
                denominator = (long) QA * QB;
            }
            // With <=512 units and the loader's bounds this product fits long.
            long cp = raw * 100L / denominator;
            return (int) Math.max(-20000L, Math.min(20000L, cp));
        }
    }
}

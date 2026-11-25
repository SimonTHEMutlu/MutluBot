package engine;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Real trained-network parity, feature updates, undo, formats and input bounds. */
public final class NnueTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("network parity.tsv");
        NnueNetwork net = NnueNetwork.load(args[0]);
        for (String line : Files.readAllLines(Paths.get(args[1]))) {
            if (line.trim().isEmpty()) continue;
            String[] fields = line.split("\t");
            Board b = new Board(); b.setFromFen(fields[0]); b.setNnueNetwork(net);
            check(Evaluator.evaluate(b) == Integer.parseInt(fields[1]), "export parity " + fields[0]);
        }
        exercise(net);
        Path fixture = fixture(2, NnueNetwork.SCRELU);
        try {
            NnueNetwork squared = NnueNetwork.load(fixture.toString());
            exercise(squared);
            byte[] bytes = Files.readAllBytes(fixture);
            reject(Arrays.copyOf(bytes, bytes.length - 1), "truncation");
            reject(Arrays.copyOf(bytes, bytes.length + 1), "trailing bytes");
            bytes[31] = 2; reject(bytes, "unsupported input buckets");
        } finally { Files.deleteIfExists(fixture); }
        for (int activation : new int[]{NnueNetwork.CRELU, NnueNetwork.SCRELU}) {
            Path bucketFixture = fixture(3, activation);
            try {
                NnueNetwork bucketNet = NnueNetwork.load(bucketFixture.toString());
                exercise(bucketNet); kingCrossings(bucketNet);
                byte[] invalid = Files.readAllBytes(bucketFixture);
                invalid[39] = 2; reject(invalid, "unsupported king bucket map");
            } finally { Files.deleteIfExists(bucketFixture); }
        }
        for (int square=0;square<64;square++)
            check(NnueNetwork.kingBucket(square)==(square>=16?3:(square%8<=2?0:square%8<=4?1:2)), "all king map squares");
        System.out.println("NnueTest passed: trained export parity; v1/v2/v3; full refresh/move/undo/null/reset/king buckets");
    }

    static Path fixture(int version, int activation) throws IOException {
        Path file = Files.createTempFile("mutlubot-own-nnue-test-", ".nnue");
        Random rng = new Random(517);
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeBytes(version == 1 ? "MUTLUN01" : version == 2 ? "MUTLUN02" : "MUTLUN03");
            out.writeInt(8); out.writeInt(255); out.writeInt(256); out.writeInt(768);
            if (version >= 2) { out.writeInt(activation); out.writeInt(version == 3 ? 4 : 1); out.writeInt(1); }
            if (version == 3) out.writeInt(1);
            for (int i = 0; i < 8; i++) out.writeInt(10 + i * 27);
            for (int i = 0; i < (version == 3 ? 4 : 1) * 768 * 8; i++) out.writeShort(rng.nextInt(101)-50);
            for (int i = 0; i < 16; i++) out.writeShort(rng.nextInt(401)-200);
            out.writeLong(-851);
        }
        return file;
    }

    private static void reject(byte[] bytes, String message) throws IOException {
        Path file = Files.createTempFile("mutlubot-invalid-nnue-test-", ".nnue");
        try {
            Files.write(file, bytes);
            try { NnueNetwork.load(file.toString()); throw new AssertionError(message); }
            catch (IOException expected) { }
        } finally { Files.deleteIfExists(file); }
    }

    private static void verify(Board b, NnueNetwork net) {
        b.nnue.flush();
        NnueNetwork.State full = new NnueNetwork.State(net,b);
        check(Arrays.deepEquals(full.accumulator,b.nnue.accumulator),"incremental/full refresh " + b.toFen());
        check(full.evaluate(b.sideToMove)==Evaluator.evaluate(b),"incremental score");
        // Independent exact scalar reference also covers the SCReLU scale/bias.
        long raw = net.outputBias * (net.activation==NnueNetwork.SCRELU ? 255L : 1L);
        for (int p=0;p<2;p++) for(int j=0;j<net.hidden;j++) {
            long a=net.bias[j];
            for(int sq=0;sq<64;sq++) if(b.mailbox[sq]>=0) {
                int code=b.mailbox[sq];
                int king=b.kingSquare(p), oriented=p==Piece.WHITE?king:king^56;
                int bucket=net.inputBuckets==1?0:NnueNetwork.kingBucket(oriented);
                a+=net.weights[(bucket*768+NnueNetwork.feature(p,code/6,code%6,sq))*net.hidden+j];
            }
            a=Math.max(0,Math.min(255,a));
            if(net.activation==NnueNetwork.SCRELU)a*=a;
            raw+=a*net.output[(p==b.sideToMove?0:net.hidden)+j];
        }
        long denominator=255L*256*(net.activation==NnueNetwork.SCRELU?255L:1L);
        check(Evaluator.evaluate(b)==(int)Math.max(-20000,Math.min(20000,raw*100/denominator)),"scalar reference");
    }

    private static void exercise(NnueNetwork net) {
        String[] fens={chessStart(),"r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1",
                "r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1",
                "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1",
                "4k3/8/8/8/3Pp3/8/8/4K3 b - d3 0 1",
                "1r2k3/P7/8/8/8/8/7p/4K3 w - - 0 1",
                "4k3/8/8/8/8/8/p7/1R2K3 b - - 0 1",
                "7k/8/8/8/8/2n5/3K4/8 w - - 0 1"};
        for(String fen:fens) {
            Board b=new Board();b.setFromFen(fen);b.setNnueNetwork(net);verify(b,net);
            NnueNetwork.State identity=b.nnue;
            MoveList moves=new MoveList();MoveGenerator.generateLegal(b,moves);
            for(int i=0;i<moves.size;i++) {
                b.makeMove(moves.get(i));verify(b,net);b.unmakeMove();verify(b,net);
                check(b.nnue==identity && b.toFen().equals(fen),"nonallocating undo");
            }
            b.makeNullMove();verify(b,net);b.unmakeNullMove();verify(b,net);
            b.setFromFen(chessStart());check(b.nnue!=null,"FEN keeps network");verify(b,net);
        }
        Random rng=new Random(717);
        for(int game=0;game<8;game++) {
            Board b=new Board();b.setNnueNetwork(net);int count=0;
            for(;count<80;count++) {
                MoveList moves=new MoveList();MoveGenerator.generateLegal(b,moves);
                if(moves.size==0)break;
                b.makeMove(moves.get(rng.nextInt(moves.size)));verify(b,net);
            }
            while(count-->0){b.unmakeMove();verify(b,net);}
            check(b.toFen().equals(chessStart()),"full history restored");
        }
        Board deferred=new Board();deferred.setNnueNetwork(net);int made=0;
        for(;made<100;made++) {
            MoveList moves=new MoveList();MoveGenerator.generateLegal(deferred,moves);
            if(moves.size==0)break;
            deferred.makeMove(moves.get(rng.nextInt(moves.size)));
        }
        verify(deferred,net);
        while(made-->0)deferred.unmakeMove();
        verify(deferred,net);
        check(deferred.toFen().equals(chessStart()),"deferred line/undo cancellation");
    }
    private static void kingCrossings(NnueNetwork net) {
        String fen="7k/8/8/8/8/8/3K4/8 w - - 0 1";
        String[] line="d2c2 h8g8 c2c3 g8f8 c3d3 f8e8 d3d2 e8d8 d2e2 d8c8 e2f2 c8b8 f2g3 b8a8 g3h3 a8a7".split(" ");
        for (boolean deferred : new boolean[]{false,true}) {
            Board b=new Board();b.setFromFen(fen);b.setNnueNetwork(net);
            for(String uci:line) {
                MoveList moves=new MoveList();MoveGenerator.generateLegal(b,moves);int chosen=-1;
                for(int i=0;i<moves.size;i++)if(Move.toUci(moves.get(i)).equals(uci))chosen=moves.get(i);
                check(chosen!=-1,"king crossing legal "+uci);b.makeMove(chosen);
                if(!deferred)verify(b,net);
            }
            verify(b,net);
            for(int i=0;i<line.length;i++){b.unmakeMove();if(!deferred)verify(b,net);}
            verify(b,net);check(b.toFen().equals(fen),"king crossing undo");
        }
    }
    private static String chessStart(){return "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";}
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
}

package engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/** Legal-EP identity, complete histories and incremental hash restoration. */
public final class CanonicalRepetitionTest {
    public static void main(String[] args) {
        String[][] fixtures = {
            {"legal white", "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", "true"},
            {"legal black", "4k3/8/8/8/3Pp3/8/8/4K3 b - d3 0 1", "true"},
            {"no capturer", "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", "false"},
            {"white file pin", "k3r3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", "false"},
            {"black file pin", "4k3/8/8/8/3Pp3/8/8/K3R3 b - d3 0 1", "false"},
            {"horizontal discovery", "k7/8/8/r4PpK/8/8/8/8 w - g6 0 1", "false"},
            {"diagonal discovery", "k1b5/8/8/4Pp2/8/7K/8/8 w - f6 0 1", "false"},
            {"one of two capturers pinned", "k3r3/8/8/2PpP3/8/8/8/4K3 w - d6 0 1", "true"},
            {"EP captures checking pawn", "4k3/8/8/3pP3/4K3/8/8/8 w - d6 0 1", "true"}
        };
        for (String[] f : fixtures) {
            Board b=board(f[1]); String before=state(b);
            boolean legal=hasLegalEp(b);
            check(legal==Boolean.parseBoolean(f[2]), "fixture EP legality: "+f[0]);
            Board noEp=board(withoutEp(f[1]));
            check((b.zobristKey!=noEp.zobristKey)==legal, "EP identity: "+f[0]);
            if(legal) check((b.zobristKey^noEp.zobristKey)==Zobrist.EP_FILE_KEY[b.epSquare&7],
                    "exact EP hash component");
            check(before.equals(state(b)), "probe/generation restoration: "+f[0]);
            b.makeNullMove(); rebuilt(b); b.unmakeNullMove();
            check(before.equals(state(b)), "null restoration: "+f[0]);
            check(f[1].equals(b.toFen()), "raw FEN EP must remain serialized");
        }
        String start=new Board().toFen();
        String white="e2e4 g8f6 g1f3 f6g8 f3g1 g8f6 g1f3 f6g8 f3g1";
        String black="e7e5 g1f3 g8f6 f3g1 f6g8 g1f3 g8f6 f3g1 f6g8";
        history(start,white,3,true);
        history(start.replace(" w "," b "),black,3,true);
        history(start,"e2e4 g8f6 g1f3 f6g8 f3g1",2,false);
        // An actual EP opportunity distinguishes the first occurrence.
        history("4k1n1/8/8/8/3p4/8/4P3/4K1N1 w - - 0 1",white,2,false);
        // Geometric EP exists, but it would expose Black's king on the d-file.
        history("3k2n1/8/8/8/3p4/8/4P3/3RK1N1 w - - 0 1",white,3,true);

        Random rng=new Random(30012026L);
        for(int game=0;game<12;game++) {
            Board b=new Board();String before=state(b);int plies=0;
            for(;plies<120;plies++) {
                rebuilt(b);
                if(b.epSquare!=-1) check((b.zobristKey!=board(withoutEp(b.toFen())).zobristKey)==hasLegalEp(b),
                        "random EP identity matches independent legal generation");
                MoveList ml=new MoveList();MoveGenerator.generateLegal(b,ml);
                if(ml.size==0)break;
                b.makeMove(ml.get(rng.nextInt(ml.size)));
                b.makeNullMove();rebuilt(b);b.unmakeNullMove();rebuilt(b);
            }
            while(plies-->0){b.unmakeMove();rebuilt(b);}
            check(before.equals(state(b)),"random complete undo");
        }
        System.out.println("CanonicalRepetitionTest passed");
    }
    private static void history(String fen,String moves,int occurrences,boolean draw) {
        Board b=board(fen);ArrayList<Long> keys=new ArrayList<>();keys.add(b.zobristKey);
        for(String uci:moves.split(" ")){b.makeMove(find(b,uci));keys.add(b.zobristKey);rebuilt(b);}
        int count=0;for(long k:keys)if(k==b.zobristKey)count++;
        check(count==occurrences,"canonical occurrence count: "+moves+" got "+count);
        String before=state(b);
        for(boolean rootIncluded:new boolean[]{true,false}) {
            long[] hist=new long[keys.size()-(rootIncluded?0:1)];
            for(int i=0;i<hist.length;i++)hist[i]=keys.get(i);
            long[] copied=hist.clone();
            Search search=new Search(new TranspositionTable(1));search.setInstrumentationEnabled(true);
            final int[] score={Integer.MIN_VALUE};search.setInfoListener((d,sd,cp,m,mi,n,nps,ms,pv)->score[0]=cp);
            int best=search.search(b,1,-1,hist);
            check(find(b,Move.toUci(best))==best,"legal repetition root bestmove");
            if(draw)check(search.getLastStats().totalNodes==1 && search.getLastStats().repetitionExits==1 && score[0]==0,
                    "claimable third occurrence must exit at root, not coincidentally evaluate equal");
            else check(search.getLastStats().totalNodes>1,"two occurrences must not be root draw");
            check(Arrays.equals(hist,copied)&&before.equals(state(b)),"board and caller history unchanged");
        }
    }
    static Board board(String fen){Board b=new Board();b.setFromFen(fen);return b;}
    static int find(Board b,String uci){MoveList m=new MoveList();MoveGenerator.generateLegal(b,m);
        for(int i=0;i<m.size;i++)if(Move.toUci(m.get(i)).equals(uci))return m.get(i);
        throw new AssertionError("illegal fixture move "+uci+" in "+b.toFen());}
    static boolean hasLegalEp(Board b){MoveList m=new MoveList();MoveGenerator.generateLegal(b,m);
        for(int i=0;i<m.size;i++)if(Move.isEnPassant(m.get(i)))return true;return false;}
    static String withoutEp(String fen){String[] p=fen.split(" ");p[3]="-";return String.join(" ",p);}
    static void rebuilt(Board b){check(board(b.toFen()).zobristKey==b.zobristKey,"incremental/recomputed canonical key");}
    static String state(Board b){return b.toFen()+":"+b.zobristKey+":"+b.pawnZobristKey
        +Arrays.deepToString(b.pieceBB)+Arrays.toString(b.occupancy)+Arrays.toString(b.mailbox)
        +":"+b.allOccupancy+":"+b.incrementalMaterialScore+":"+b.incrementalMgPstScore
        +":"+b.incrementalEgPstScore+":"+b.phaseUnits;}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}

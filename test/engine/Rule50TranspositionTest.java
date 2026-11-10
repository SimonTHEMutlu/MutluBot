package engine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Clock validity is separate from position/repetition identity and move hints. */
public final class Rule50TranspositionTest {
    static final String ROOK="7k/8/8/8/8/8/R7/K7 w - - 0 1";
    public static void main(String[] args)throws Exception {
        long identity=clockBoard(0).zobristKey;
        for(int clock:new int[]{0,98,99,100})check(clockBoard(clock).zobristKey==identity,
                "halfmove clock must never enter repetition identity");
        for(int clock:new int[]{0,98,99,100}) {
            TranspositionTable tt=new TranspositionTable(1);
            for(int flag:new int[]{TranspositionTable.EXACT,TranspositionTable.LOWER_BOUND,TranspositionTable.UPPER_BOUND}) {
                tt.store(0,128,-30000,flag,0xffff,clock);
                long p=tt.probePacked(0);
                check(p!=0 && TranspositionTable.halfmoveClockOf(p)==clock
                        && TranspositionTable.depthOf(p)==128 && TranspositionTable.scoreOf(p)==-30000
                        && TranspositionTable.moveOf(p)==0xffff && TranspositionTable.flagOf(p)==flag,
                        "all packed fields survive clock metadata and signed valid bit");
            }
        }
        TranspositionTable replacement=new TranspositionTable(1);
        replacement.store(identity,20,123,TranspositionTable.EXACT,1,0);
        replacement.store(identity,1,456,TranspositionTable.LOWER_BOUND,2,99);
        check(TranspositionTable.scoreOf(replacement.probePacked(identity))==456,
                "different clock must not be protected as a deeper usable score");
        replacement.store(identity,1,789,TranspositionTable.UPPER_BOUND,3,99);
        check(TranspositionTable.halfmoveClockOf(replacement.probePacked(identity))==99,"clock is independent of bounds");
        replacement.clear();replacement.store(identity,1,1,TranspositionTable.EXACT,1,99);
        for(int i=0;i<(1<<14);i++)replacement.newSearch();
        check(replacement.probePacked(identity)==0,"generation wrap clears before aliasing");

        for(int storedClock:new int[]{0,98,99})for(int actualClock:new int[]{0,98,99,100}) {
            Board b=clockBoard(actualClock);TranspositionTable tt=new TranspositionTable(1);
            int hint=CanonicalRepetitionTest.find(b,"a2a3");
            tt.store(b.zobristKey,3,777,TranspositionTable.EXACT,hint,storedClock);
            Search s=search(tt);int score=main(s,b,1,-32000,32000,0);
            if(actualClock==100)check(score==0,"draw check precedes TT");
            else if(storedClock==actualClock)check(score==777 && stat(s,"ttExactHits")==1,
                    "matching context still reuses exact score");
            else check(score!=777 && stat(s,"ttExactHits")==0,
                    "clock mismatch must reject exact score but may retain legal move hint");
        }
        for(int flag:new int[]{TranspositionTable.LOWER_BOUND,TranspositionTable.UPPER_BOUND}) {
            Board b=clockBoard(99);TranspositionTable tt=new TranspositionTable(1);
            int fake=flag==TranspositionTable.LOWER_BOUND?777:-777;
            tt.store(b.zobristKey,3,fake,flag,CanonicalRepetitionTest.find(b,"a2a3"),0);
            Search s=search(tt);
            int score=main(s,b,1,flag==TranspositionTable.LOWER_BOUND?0:-1,
                    flag==TranspositionTable.LOWER_BOUND?1:0,0);
            check(score==0 && stat(s,"ttBoundCutoffs")==0,"clock mismatch must reject both bound types");
        }
        // Reproduce the report exactly: same Search, same generation, different clock.
        TranspositionTable shared=new TranspositionTable(1);Search s=search(shared);
        s.search(clockBoard(0),1,-1,null);
        int cached=main(s,clockBoard(99),1,-32000,32000,0);
        int fresh=main(search(new TranspositionTable(1)),clockBoard(99),1,-32000,32000,0);
        check(cached==fresh && fresh==0,"same-generation 0 -> 99 must not return old rook advantage");

        Board root=new Board();int first=CanonicalRepetitionTest.find(root,"e2e4");
        Board child=CanonicalRepetitionTest.board(root.toFen());child.makeMove(first);
        int reply=CanonicalRepetitionTest.find(child,"a7a6");
        TranspositionTable pvTt=new TranspositionTable(1);Search pvSearch=new Search(pvTt);
        pvTt.store(child.zobristKey,4,0,TranspositionTable.EXACT,reply,child.halfmoveClock);
        check(pv(pvSearch,root,first).equals("e2e4 a7a6"),"matching clock may extend legal PV");
        pvTt.store(child.zobristKey,4,0,TranspositionTable.EXACT,reply,child.halfmoveClock+1);
        check(pv(pvSearch,root,first).equals("e2e4"),"different clock cannot fabricate an exact PV");

        for(String fen:new String[]{"7k/8/8/8/8/8/P7/KR6 w - - 99 1",
                "7k/1p6/8/8/8/8/8/KR6 w - - 99 1"}) {
            Board b=CanonicalRepetitionTest.board(fen);String before=CanonicalRepetitionTest.state(b);
            Search zeroing=search(new TranspositionTable(1));final int[] score={0};
            zeroing.setInfoListener((d,sd,cp,m,mi,n,nps,ms,p)->score[0]=cp);
            int move=zeroing.search(b,2,-1,null);
            check(score[0]>0 && (Move.isCapture(move)||b.pieceTypeAt(Move.from(move))==Piece.PAWN),
                    "capture/pawn move resets clock and preserves winning continuation");
            b.makeMove(move);check(b.halfmoveClock==0,"zeroing clock");b.unmakeMove();
            check(before.equals(CanonicalRepetitionTest.state(b)),"zeroing restoration");
        }
        Board mate=CanonicalRepetitionTest.board("7k/6Q1/6K1/8/8/8/8/8 b - - 100 1");
        check(main(search(new TranspositionTable(1)),mate,1,-32000,32000,7)==-Evaluator.MATE_SCORE+7,
                "checkmate precedes rule50 and TT");
        for(int ply:new int[]{1,7,63}) {
            Board b=clockBoard(0);TranspositionTable tt=new TranspositionTable(1);
            tt.store(b.zobristKey,5,Evaluator.MATE_SCORE-3,TranspositionTable.EXACT,Move.NONE,0);
            check(main(search(tt),b,1,-32000,32000,ply)==Evaluator.MATE_SCORE-3-ply,
                    "mate normalization survives clock metadata");
        }
        System.out.println("Rule50TranspositionTest passed");
    }
    static Board clockBoard(int n){return CanonicalRepetitionTest.board(ROOK.replace("0 1",n+" 1"));}
    static Search search(TranspositionTable tt)throws Exception {
        Search s=new Search(tt);s.setInstrumentationEnabled(true);s.setProphylaxisProbeEnabled(false);
        Field f=Search.class.getDeclaredField("keyStack");f.setAccessible(true);f.set(s,new long[Search.MAX_PLY+8]);return s;
    }
    static int main(Search s,Board b,int d,int a,int beta,int ply)throws Exception {
        String before=CanonicalRepetitionTest.state(b);
        Method m=Search.class.getDeclaredMethod("negamax",Board.class,int.class,int.class,int.class,int.class,boolean.class);
        m.setAccessible(true);int result=(Integer)m.invoke(s,b,d,a,beta,ply,true);
        check(before.equals(CanonicalRepetitionTest.state(b)),"TT/context search restores full board");return result;
    }
    static long stat(Search s,String name)throws Exception{Field f=Search.class.getDeclaredField(name);f.setAccessible(true);return f.getLong(s);}
    static String pv(Search s,Board b,int first)throws Exception{Method m=Search.class.getDeclaredMethod("extractPv",Board.class,int.class,int.class);m.setAccessible(true);return(String)m.invoke(s,b,3,first);}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}

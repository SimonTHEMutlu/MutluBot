package engine;

import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/** Search integration using the actual supplied network and independent proofs. */
public final class NnueSearchTest {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("our-network.nnue");
        NnueNetwork net=NnueNetwork.load(args[0]);
        terminal(net,"7k/6Q1/6K1/8/8/8/8/8 b - - 100 1",-Evaluator.MATE_SCORE);
        terminal(net,"7k/5Q2/6K1/8/8/8/8/8 b - - 0 1",0);
        Method q=Search.class.getDeclaredMethod("quiescence",Board.class,int.class,int.class,int.class);q.setAccessible(true);
        for(String fen:new String[]{"7k/6Q1/6K1/8/8/8/8/8 b - - 0 1","7k/8/8/7R/8/8/8/K7 b - - 0 1"}) {
            Board b=board(fen,net);int before=Evaluator.evaluate(b);long key=b.zobristKey;
            int value=(Integer)q.invoke(new Search(new TranspositionTable(1)),b,-Evaluator.INFINITY_SCORE,Evaluator.INFINITY_SCORE,7);
            if(fen.startsWith("7k/6Q1"))check(value==-Evaluator.MATE_SCORE+7,"NNUE qsearch mate/ply");
            else check(value>-Evaluator.MATE_SCORE+Search.MAX_PLY,"quiet evasions are not mate");
            restored(b,fen,key,before,net);
        }
        for(String line:Files.readAllLines(Paths.get("test/resources/nnue-mate-proofs.tsv"))) {
            if(line.trim().isEmpty()||line.startsWith("#"))continue;
            String[] fields=line.split("\t");String fen=fields[0];int matePlies=Integer.parseInt(fields[1]);
            Set<String> accepted=new HashSet<>(Arrays.asList(fields[2].split(",")));
            Board b=board(fen,net);int before=Evaluator.evaluate(b);long key=b.zobristKey;
            Search search=new Search(new TranspositionTable(16));search.setNnueNetwork(net);
            int[] score={Integer.MIN_VALUE};String[] pv={""};
            search.setInfoListener((d,sd,s,m,mi,n,nps,t,p)->{score[0]=s;pv[0]=p;});
            int best=search.search(b,6,-1,new long[]{key});
            check(accepted.contains(Move.toUci(best)),"proved mate key: "+Move.toUci(best)+" in "+fen);
            check(score[0]>=Evaluator.MATE_SCORE-matePlies,"proved mate bound "+score[0]);
            check(!pv[0].trim().isEmpty(),"proved mate PV present");
            for(String word:pv[0].trim().split("\\s+")) b.makeMove(find(b,word));
            for(String word:pv[0].trim().split("\\s+")) b.unmakeMove();
            restored(b,fen,key,before,net);
        }
        Board history=new Board();history.setNnueNetwork(net);long[] keys=new long[9];keys[0]=history.zobristKey;
        String[] cycle="g1f3 g8f6 f3g1 f6g8 g1f3 g8f6 f3g1 f6g8".split(" ");
        for(int i=0;i<cycle.length;i++){history.makeMove(find(history,cycle[i]));keys[i+1]=history.zobristKey;}
        String fen=history.toFen();int before=Evaluator.evaluate(history);Search repeated=new Search(new TranspositionTable(1));
        int[] value={Integer.MIN_VALUE};repeated.setInfoListener((d,sd,s,m,mi,n,nps,t,p)->value[0]=s);
        int best=repeated.search(history,4,-1,keys);check(value[0]==0,"full-history repetition with NNUE");
        check(best!=Move.NONE,"legal fallback for repetition claim");restored(history,fen,keys[8],before,net);
        System.out.println("NnueSearchTest passed: actual network; terminal/qsearch/full history; exhaustive mate-in-two proofs");
    }
    private static void terminal(NnueNetwork net,String fen,int expected) {
        Board b=board(fen,net);int before=Evaluator.evaluate(b);long key=b.zobristKey;
        Search search=new Search(new TranspositionTable(1));int[] value={Integer.MIN_VALUE};
        search.setInfoListener((d,sd,s,m,mi,n,nps,t,p)->value[0]=s);
        check(search.search(b,4,-1,new long[]{key})==Move.NONE,"terminal no bestmove");
        check(value[0]==expected,"terminal score");restored(b,fen,key,before,net);
    }
    private static int find(Board b,String word) {
        MoveList list=new MoveList();MoveGenerator.generateLegal(b,list);
        for(int i=0;i<list.size;i++)if(Move.toUci(list.get(i)).equals(word))return list.get(i);
        throw new AssertionError("illegal move/PV "+word+" in "+b.toFen());
    }
    private static Board board(String fen,NnueNetwork net){Board b=new Board();b.setFromFen(fen);b.setNnueNetwork(net);return b;}
    private static void restored(Board b,String fen,long key,int score,NnueNetwork net) {
        check(b.toFen().equals(fen)&&b.zobristKey==key,"board/history restored");
        check(b.nnue!=null&&b.nnue.network==net&&Evaluator.evaluate(b)==score,"NNUE state restored");
    }
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
}

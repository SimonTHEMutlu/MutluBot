package engine;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;

/** NNUE option lifetime, active-network evidence, TT isolation and search copy. */
public final class UciNnueProtocolTest {
    private static final Method COMMAND;
    static {
        try { COMMAND=UCIEngine.class.getDeclaredMethod("handleCommand",String.class);COMMAND.setAccessible(true); }
        catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    public static void main(String[] args)throws Exception {
        PrintStream original=System.out;
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        UCIEngine engine=new UCIEngine();
        Path positive=biasNetwork(1000),negative=biasNetwork(-1000);
        try(PrintStream capture=new PrintStream(bytes,true,"UTF-8")) {
            System.setOut(capture);
            command(engine,"uci");
            check(bytes.toString("UTF-8").contains("option name UseNNUE type check default false"),"option advertised");
            command(engine,"setoption name UseNNUE value true");
            check(board(engine).nnue==null,"unloaded request is pending");
            command(engine,"setoption name EvalFile value "+positive);
            check(board(engine).nnue!=null,"load activates pending request");
            check(bytes.toString("UTF-8").contains("sha256 "+board(engine).nnue.network.sha256),"active hash advertised");
            run(engine,bytes,-1000);
            NnueNetwork.State state=board(engine).nnue;
            command(engine,"setoption name EvalFile value "+positive+".missing");
            check(board(engine).nnue==state,"load failure retains working network");
            command(engine,"setoption name UseNNUE value nonsense");
            check(board(engine).nnue==state,"invalid boolean is transactional");
            TranspositionTable tt=(TranspositionTable)field(engine,"tt");
            long key=board(engine).zobristKey;
            tt.store(key,8,777,TranspositionTable.EXACT,Move.NONE);
            command(engine,"setoption name EvalFile value "+negative);
            check(tt.probePacked(key)==0,"new evaluator clears old bounds");
            run(engine,bytes,1000);
            command(engine,"setoption name Hash value 16");
            command(engine,"ucinewgame");
            run(engine,bytes,1000);
            command(engine,"go ponder depth 1");
            Thread ponder=(Thread)field(engine,"searchThread");
            command(engine,"setoption name UseNNUE value false");
            check(!ponder.isAlive() && board(engine).nnue==null,"toggle joins ponder and disables");
            command(engine,"position startpos");
            bytes.reset();command(engine,"go depth 1");join(engine);
            check(!bytes.toString("UTF-8").contains("score cp 1000"),"HCE restored");
            command(engine,"setoption name EvalFile value <empty>");
            command(engine,"setoption name UseNNUE value true");
            check(board(engine).nnue==null,"empty network cannot silently remain active");
        } finally {
            command(engine,"quit");System.setOut(original);
            Files.deleteIfExists(positive);Files.deleteIfExists(negative);
        }
        System.out.println("UciNnueProtocolTest passed");
    }
    private static Path biasNetwork(int cp)throws IOException {
        Path path=Files.createTempFile("mutlubot network with spaces ",".nnue");
        try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(path))) {
            out.writeBytes("MUTLUN01");out.writeInt(1);out.writeInt(255);out.writeInt(256);out.writeInt(768);
            out.writeInt(0);for(int i=0;i<770;i++)out.writeShort(0);
            out.writeLong((long)cp*255*256/100);
        }
        return path;
    }
    private static void run(UCIEngine engine,ByteArrayOutputStream bytes,int expected)throws Exception {
        command(engine,"position startpos");bytes.reset();command(engine,"go depth 1");join(engine);
        String log=bytes.toString("UTF-8");
        check(log.contains("score cp "+expected+" "),"search board must use selected evaluator: "+log);
        int count=0;for(String line:log.split("\\R"))if(line.startsWith("bestmove ")) {
            count++;String move=line.split(" ")[1];MoveList legal=new MoveList();MoveGenerator.generateLegal(board(engine),legal);
            boolean found=false;for(int i=0;i<legal.size;i++)found|=Move.toUci(legal.get(i)).equals(move);
            check(found,"legal bestmove");
        }
        check(count==1,"one bestmove");
    }
    private static void join(UCIEngine engine)throws Exception {
        Thread thread=(Thread)field(engine,"searchThread");thread.join(10000);
        check(!thread.isAlive(),"search completed");
    }
    private static Board board(UCIEngine e)throws Exception{return (Board)field(e,"board");}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void command(UCIEngine e,String cmd)throws Exception{COMMAND.invoke(e,cmd);}
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
}

package lang;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real CLI processes verify exit codes; API tests cover signed exact bindings. */
public final class LanguageRegressionTest {
    private static int checks;
    private static void require(boolean ok,String name) { checks++;if(!ok)throw new AssertionError(name); }
    private static final class Outcome {
        final int code; final String output;
        Outcome(int code,String output){this.code=code;this.output=output;}
    }
    private static Outcome run(Path directory,String... args) throws Exception {
        String[] entries=System.getProperty("java.class.path").split(java.io.File.pathSeparator);
        for(int i=0;i<entries.length;i++)entries[i]=Path.of(entries[i]).toAbsolutePath().toString();
        List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",String.join(java.io.File.pathSeparator,entries),"lang.Interpreter"));
        Collections.addAll(command,args);
        Path log=directory.resolve("cli.log");
        Process process=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(15,TimeUnit.SECONDS)) {
            process.destroyForcibly();process.waitFor();throw new AssertionError("CLI timed out");
        }
        return new Outcome(process.exitValue(),Files.readString(log));
    }
    public static void main(String[] args) throws Exception {
        PrintStream original=System.out;ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(PrintStream capture=new PrintStream(bytes,true,StandardCharsets.UTF_8)) {
            System.setOut(capture);
            for(String[] sample:new String[][] {
                    {"eval x where x=-3/4;","-3/4"}, {"eval x where x=+3/-4;","-3/4"},
                    {"eval x where x=-3/-4;","3/4"}, {"eval x+y where x=-1/2,y=+1/3;","-1/6"},
                    {"eval x where x=18446744073709551616;","18446744073709551616"},
                    {"assume x != 0; eval x/x where x=-2/3;","1"}}) {
                List<Object> result=Interpreter.executeProgram(sample[0],new Context());
                require(result.get(result.size()-1).equals(Rational.of(sample[1])),"exact binding: "+sample[0]);
            }
            try { Interpreter.executeProgram("eval x where x=1/0;",new Context());throw new AssertionError("zero denominator"); }
            catch(ArithmeticException expected){checks++;}
            try { Interpreter.executeProgram("eval x where x=1,x=2;",new Context());throw new AssertionError("duplicate bindings"); }
            catch(IllegalArgumentException expected){checks++;}
            try { Interpreter.executeProgram("eval 1/0; print x;",new Context());throw new AssertionError("eval error swallowed"); }
            catch(ArithmeticException expected){checks++;}
        } finally {System.setOut(original);}
        require(!bytes.toString(StandardCharsets.UTF_8).contains("[Print] x"),"evaluation stops execution on error");
        try { Interpreter.parseString("x".repeat(Interpreter.MAX_SOURCE_CHARS+1));throw new AssertionError("source limit"); }
        catch(SearchLimitException expected){require(expected.reason==EGraph.StopReason.INPUT_LIMIT,"text admission");}
        try { Interpreter.parseString("9".repeat(20001));throw new AssertionError("literal limit"); }
        catch(SearchLimitException expected){require(expected.reason==EGraph.StopReason.INTEGER_LIMIT,"literal admission");}

        Path directory=Files.createTempDirectory("symsat-cli-");
        try {
            Path file=directory.resolve("Türkçe program.sym");
            Files.writeString(file,"# Negatif rasyonel değer\neval x where x=-3/4;\n");
            Outcome read=run(directory,"--file",file.toString());
            require(read.code==0 && read.output.contains("==> -3/4"),"UTF-8 program file");
            Files.writeString(directory.resolve("prog.txt"),"simplify 2/2;");
            require(run(directory).code==0,"default program file");
            Outcome expression=run(directory,"(x*2)/2");
            require(expression.code==0 && expression.output.contains("==> x"),"literal expression CLI");
            require(!expression.output.contains("d/dx") && !expression.output.contains("Classical"),"no implicit derivative/greedy execution");
            require(run(directory,"--help").code==0,"help status");
            require(run(directory,"--unknown").code==2,"unknown option status");
            require(run(directory,"--file").code==2,"missing file argument status");
            require(run(directory,"x","y").code==2,"extra arguments status");
            require(run(directory,"--file",directory.resolve("missing.sym").toString()).code==1,"missing file status");
            Outcome syntax=run(directory,"simplify x+;");
            require(syntax.code==1 && !syntax.output.contains("Exception in thread"),"syntax error status");
            Outcome lexical=run(directory,"@");
            require(lexical.code==1 && lexical.output.contains("Lexical error"),"lexer error status");
            require(run(directory,"eval 1/0;").code==1,"evaluation failure status");
            require(run(directory,"assume x != 0; eval 1 where x=0;").code==1,"assumption violation status");
            require(run(directory,"eval 2^1000000000;").code==3,"arithmetic resource status");
            Outcome bounded=run(directory,"simplify (x+y)*(a+b)*(c+d);");
            require(bounded.code==3 && bounded.output.contains("Search incomplete:"),"bounded search has resource exit code");
            Outcome nesting=run(directory,"(".repeat(1000)+"x"+")".repeat(1000));
            require(nesting.code==3 && !nesting.output.contains("StackOverflowError"),"parentheses nesting guard");
            require(run(directory,"+"+"-".repeat(1000)+"x").code==3,"unary recursion guard");
            require(run(directory,"2^".repeat(1000)+"2").code==3,"power recursion guard");
            Path oversized=directory.resolve("oversized.sym");
            Files.writeString(oversized," ".repeat(Interpreter.MAX_SOURCE_CHARS+1));
            require(run(directory,"--file",oversized.toString()).code==3,"bounded source file read");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(directory)) {
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);
            }
        }
        System.out.println("PASS: "+checks+" language and CLI checks");
    }
}

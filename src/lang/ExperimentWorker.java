package lang;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Line-based worker for frozen local experiments; input parsing is outside solve timing. */
public final class ExperimentWorker {
    private static final List<Rule> RULES=Interpreter.getDefaultEquivalenceRules();
    private static String quote(String value) {
        StringBuilder out=new StringBuilder("\"");
        for(char c:value.toCharArray()) {
            if(c=='"'||c=='\\')out.append('\\').append(c);
            else if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
    public static void main(String[] args) throws IOException {
        BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
        PrintWriter output=new PrintWriter(new OutputStreamWriter(System.out,StandardCharsets.UTF_8),true);
        for(String line;(line=input.readLine())!=null;) {
            String[] fields=line.split("\t",3);
            try {
                if(fields.length!=3)throw new IllegalArgumentException("Expected id, engine, expression");
                Exp expression=Interpreter.parseString(fields[2]);
                SearchLimits.defaults().validateInput(expression);
                if(fields[1].equals("egraph")) {
                    Context context=new Context(RULES);
                    long start=System.nanoTime();
                    SimplificationResult result=context.simplifyDetailed(expression,CostFunction.AST_SIZE);
                    long elapsed=System.nanoTime()-start;
                    output.println("{\"id\":"+quote(fields[0])+",\"engine\":\"egraph\",\"status\":\"OK\","
                            +"\"elapsed_ns\":"+elapsed+",\"expression\":"+quote(result.expression.toString())
                            +",\"search\":"+result.toJson()+"}");
                } else if(fields[1].equals("local_greedy")) {
                    long start=System.nanoTime();
                    Exp result=new SimplifyVisitor().simplify(expression);
                    long elapsed=System.nanoTime()-start;
                    SearchLimits.defaults().validateInput(result);
                    output.println("{\"id\":"+quote(fields[0])+",\"engine\":\"local_greedy\",\"status\":\"OK\","
                            +"\"elapsed_ns\":"+elapsed+",\"expression\":"+quote(result.toString())+"}");
                } else throw new IllegalArgumentException("Unknown engine");
            } catch(Exception | TokenMgrError error) {
                output.println("{\"id\":"+quote(fields.length>0?fields[0]:"")+",\"status\":\"ERROR\","
                        +"\"error\":"+quote(error.getClass().getSimpleName()+": "+error.getMessage())+"}");
            }
        }
    }
}

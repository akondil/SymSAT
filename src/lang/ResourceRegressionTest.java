package lang;

import java.math.BigInteger;
import java.util.*;

public final class ResourceRegressionTest {
    private static int checks;
    private static Exp p(String text) throws ParseException { return Interpreter.parseString(text); }
    private static void require(boolean ok,String message) {
        checks++; if(!ok)throw new AssertionError(message);
    }
    private static void rejects(Runnable action,EGraph.StopReason reason,String message) {
        try{action.run();throw new AssertionError(message+": accepted");}
        catch(SearchLimitException error){require(error.reason==reason,message+": "+error.reason);}
    }
    private static SearchLimits limits(long work,long bindings,int bits,int depth,int nodes) {
        return new SearchLimits(10,100,work,bindings,bits,depth,nodes);
    }
    public static void main(String[] args) throws Exception {
        SearchLimits tinyGraph=new SearchLimits(10,2);
        Context input=new Context(Collections.emptyList(),tinyGraph);
        Exp sum=p("x+y");
        rejects(()->input.simplifyDetailed(sum,null),EGraph.StopReason.NODE_LIMIT,"input node admission");
        Context.EquivalenceResult refused=input.checkEquivalence(sum,sum);
        require(refused.status==Context.EquivalenceStatus.INCOMPLETE,"oversized identical query is incomplete");
        require(refused.saturation.enodeCount<=2 && refused.saturation.stopReason==EGraph.StopReason.NODE_LIMIT,"partial input graph obeys cap");
        EGraph graph=new EGraph(Collections.emptySet(),tinyGraph);
        rejects(()->graph.add(sum),EGraph.StopReason.NODE_LIMIT,"direct graph admission cap");
        require(graph.totalNodeCount()<=2,"direct cap holds");

        SearchLimits shape=limits(10000,10000,64,8,12);
        Context shapes=new Context(Collections.emptyList(),shape);
        Exp deep=new Symbol("x");for(int i=0;i<1000;i++)deep=new UnaryMinus(deep);
        final Exp tooDeep=deep;
        rejects(()->shapes.simplifyDetailed(tooDeep,null),EGraph.StopReason.INPUT_LIMIT,"iterative depth admission");
        Exp wide=new FunctionCall("f",Collections.nCopies(1000,new Symbol("x")));
        rejects(()->shapes.simplifyDetailed(wide,null),EGraph.StopReason.INPUT_LIMIT,"wide input admission");
        require(shapes.checkEquivalence(tooDeep,p("x")).status==Context.EquivalenceStatus.INCOMPLETE,"deep proof input");
        rejects(()->shapes.defineVar("bad",tooDeep),EGraph.StopReason.INPUT_LIMIT,"deep let rejected");
        require(!shapes.hasVar("bad"),"let rejection atomic");

        SearchLimits smallBits=limits(100000,100000,64,64,1000);
        Context arithmetic=new Context(Collections.emptyList(),smallBits);
        Exp hugePower=p("2^1000000000");
        SimplificationResult power=arithmetic.simplifyDetailed(hugePower,null);
        require(power.saturation.stopReason==EGraph.StopReason.INTEGER_LIMIT,"power guarded before allocation");
        require(power.expression.equals(hugePower) && !power.isSaturated(),"unfolded expression preserved");
        require(power.saturation.enodeCount==3,"huge power did not create huge constant");
        require(arithmetic.checkEquivalence(hugePower,p("0")).status==Context.EquivalenceStatus.INCOMPLETE,"integer limit proof status");
        rejects(()->arithmetic.evaluate(hugePower,Collections.emptyMap()),EGraph.StopReason.INTEGER_LIMIT,"eval power guarded");
        Exp hugeConstant=new Const(new Rational(BigInteger.ONE.shiftLeft(100),BigInteger.ONE));
        rejects(()->arithmetic.simplifyDetailed(hugeConstant,null),EGraph.StopReason.INTEGER_LIMIT,"input integer admission");
        Exp product=p("4294967296*4294967296");
        require(arithmetic.simplifyDetailed(product,null).saturation.stopReason==EGraph.StopReason.INTEGER_LIMIT,"multiplication guarded");
        rejects(()->arithmetic.evaluate(product,Collections.emptyMap()),EGraph.StopReason.INTEGER_LIMIT,"eval multiplication guarded");
        require(arithmetic.evaluate(p("2^10+1/2"),Collections.emptyMap()).equals(Rational.of("2049/2")),"valid arithmetic exact");
        require(arithmetic.simplifyDetailed(p("2^10"),null).expression.equals(p("1024")),"valid folding");
        require(!new Context().proveEquivalence(p("0/0"),p("1")),"domain guard retained");

        Context matching=new Context(List.of(Rule.forward("expand","f(?x)","g(?x)")),limits(10000,1,64,64,1000));
        SimplificationResult match=matching.simplifyDetailed(p("f(x)"),null);
        require(match.saturation.stopReason==EGraph.StopReason.MATCH_LIMIT,"binding stop");
        require(match.saturation.bindingRecords<=1,"binding counter cap");
        require(match.expression.equals(p("f(x)")),"matching failure preserves input");
        require(matching.checkEquivalence(p("f(x)"),p("g(x)")).status==Context.EquivalenceStatus.INCOMPLETE,"binding stop proof");

        List<Rule> many=new ArrayList<>();
        for(int i=0;i<100;i++)many.add(Rule.forward("r"+i,"f(?x)","g(?x)"));
        Context work=new Context(many,limits(64,10000,64,64,1000));
        SimplificationResult stopped=work.simplifyDetailed(p("f(x)"),null);
        require(stopped.saturation.stopReason==EGraph.StopReason.WORK_LIMIT,"work stop");
        require(stopped.saturation.workUnits==64,"work counter cap");
        require(work.checkEquivalence(p("f(x)"),p("g(x)")).status==Context.EquivalenceStatus.INCOMPLETE,"work stop proof");
        require(stopped.toJson().contains("\"max_work\":64") && stopped.toJson().contains("\"work_units\":64"),"JSON budget provenance");
        require(stopped.summary().contains("work=64"),"visible work count");

        EGraph deepGraph=new EGraph(Collections.emptySet(),shape);
        int chain=deepGraph.add(new Const(1));
        for(int i=0;i<20;i++)chain=deepGraph.addENode(new ENode("neg",List.of(chain)));
        final int deepRoot=chain;
        rejects(()->new Extractor(deepGraph,CostFunction.AST_SIZE).extract(deepRoot),
                EGraph.StopReason.INPUT_LIMIT,"extraction recursion limit");
        EGraph extractionWork=new EGraph(Collections.emptySet(),limits(1,10000,64,64,1000));
        extractionWork.add(sum);
        rejects(()->new Extractor(extractionWork,CostFunction.AST_SIZE),EGraph.StopReason.WORK_LIMIT,"extraction cost work limit");

        // Exhaustion after a first rewrite must repair congruence and leave reusable graph state.
        EGraph reusable=new EGraph(Collections.emptySet(),limits(100000,100000,64,64,1000));
        int a=reusable.add(p("f(x)")),b=reusable.add(p("g(x)"));
        List<Rule> rules=List.of(Rule.forward("first","f(?x)","g(?x)"),Rule.forward("large","g(?x)","2^1000000000"));
        EGraph.SaturationResult halted=reusable.saturateWithResult(rules,10,100);
        require(halted.stopReason==EGraph.StopReason.INTEGER_LIMIT,"partial batch resource stop");
        require(reusable.find(a)==reusable.find(b),"completed rewrite retained");
        require(new Extractor(reusable,CostFunction.AST_SIZE).extract(a)!=null,"graph extractable after stop");
        require(reusable.saturateWithResult(Collections.emptyList(),1,100).stopReason==EGraph.StopReason.INTEGER_LIMIT,"budget resets on repeated search");
        System.out.println("PASS: "+checks+" resource limit checks");
    }
}

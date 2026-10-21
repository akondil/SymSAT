package lang;

import java.math.BigInteger;
import java.util.*;

/** Executable semantic regressions. A failure terminates with a nonzero exit code. */
public final class RegressionTest {
    private static int checks;
    private static Exp p(String s) throws ParseException { return Interpreter.parseString(s); }
    private static void require(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    private static void fails(Class<? extends Throwable> type, Runnable f, String description) {
        try { f.run(); } catch (Throwable e) { require(type.isInstance(e), description + ": wrong exception " + e); return; }
        require(false, description + ": no exception");
    }
    public static void main(String[] args) throws Exception {
        require(Rational.of("-6/-8").equals(Rational.of("3/4")), "rational normalization");
        require(Rational.of("1/3").add(Rational.of("1/6")).equals(Rational.of("1/2")), "exact addition");
        require(new Rational(0).pow(0).equals(Rational.ONE), "0^0 convention");
        require(Rational.of("2/3").pow(-2).equals(Rational.of("9/4")), "negative exponent");
        fails(ArithmeticException.class, () -> Rational.ZERO.divide(Rational.ZERO), "0/0 rejected");
        fails(ArithmeticException.class, () -> Rational.ONE.divide(Rational.ZERO), "1/0 rejected");
        fails(ArithmeticException.class, () -> Rational.ZERO.pow(-1), "0^-1 rejected");
        fails(ArithmeticException.class, () -> Rational.ONE.pow(Integer.MIN_VALUE), "min int exponent rejected");
        fails(ArithmeticException.class, () -> Rational.ONE.pow(new BigInteger("2147483648")), "large exponent rejected");
        fails(ArithmeticException.class, () -> new Rational(BigInteger.ZERO, BigInteger.ZERO), "zero denominator constructor");
        require(p("x/3 + x/6").accept(new EvalVisitor(Map.of("x",new Rational(3)))).equals(Rational.of("3/2")), "exact evaluator");
        fails(UnsupportedOperationException.class, () -> new FunctionCall("sin",List.of(new Const(1),new Const(2))).accept(new DiffVisitor("x")), "derivative arity");
        fails(UnsupportedOperationException.class, () -> new FunctionCall("sin",Collections.emptyList()).accept(new DiffVisitor("x")), "empty derivative arity");
        fails(UnsupportedOperationException.class, () -> CodeGenerator.toJavaMethod("f", new FunctionCall("sin",List.of(new Const(1),new Const(2))),List.of()), "Java arity");
        fails(IllegalArgumentException.class, () -> Rule.forward("bad", "?x", "?y"), "forward variable scope");
        fails(IllegalArgumentException.class, () -> Rule.bidirectional("bad", "?x+?y", "?x"), "reverse variable scope");
        for (String expr : List.of("x+0","0+x","x*1","1*x")) {
            Context context = new Context(List.of(Rule.forward("zero","?x+0","?x"),Rule.forward("one","?x*1","?x")));
            require(context.checkEquivalence(p(expr),p("x")).status == Context.EquivalenceStatus.PROVED,"commutative literal match " + expr);
        }
        Context empty = new Context(Collections.emptyList());
        require(empty.checkEquivalence(p("x"),p("y")).status == Context.EquivalenceStatus.NOT_DERIVED,"fixed point non derivation");
        require(empty.checkEquivalence(p("x+y"),p("y+x")).saturation.iterations == 0,"initial commutative merge");
        require(empty.checkEquivalence(p("x-x"),p("0")).status == Context.EquivalenceStatus.NOT_DERIVED,"no implicit subtraction theory");
        Context shortSearch = new Context(List.of(Rule.forward("a","f(?x)","g(?x)"),Rule.forward("b","g(?x)","h(?x)")),1,100);
        require(shortSearch.checkEquivalence(p("f(x)"),p("h(x)")).status == Context.EquivalenceStatus.INCOMPLETE,"iteration limit");
        Context tiny = new Context(List.of(Rule.bidirectional("growth","?x*(?y+?z)","?x*?y+?x*?z")),20,12);
        Context.EquivalenceResult limited = tiny.checkEquivalence(p("(a+b)*(c+d)"),p("y"));
        require(limited.status == Context.EquivalenceStatus.INCOMPLETE,"node limit status");
        require(limited.saturation.enodeCount <= 12,"insertion cap");
        require(limited.saturation.stopReason == EGraph.StopReason.NODE_LIMIT,"node stop reason");
        EGraph graph = new EGraph(); int root = graph.add(p("x+0"));
        graph.saturateWithResult(List.of(Rule.forward("zero","?x+0","?x")),10,100);
        Extractor extractor = new Extractor(graph,CostFunction.AST_SIZE);
        require(extractor.extract(root).equals(p("x")),"cyclic extraction");
        require(extractor.getCost(root) == 1.0,"cyclic minimum cost");
        EGraph congruence = new EGraph(); int fx = congruence.add(p("f(x)")), fy = congruence.add(p("f(y)"));
        congruence.union(congruence.add(p("x")),congruence.add(p("y"))); congruence.rebuild();
        require(congruence.find(fx) == congruence.find(fy),"congruence rebuild");
        EGraph stationary = new EGraph(); stationary.add(p("x"));
        require(stationary.saturateWithResult(Collections.emptyList(),10,100).stopReason == EGraph.StopReason.FIXED_POINT,"fixed point counter");
        require(PolynomialVerifier.check("x*(y+z)","x*y+x*z").status == PolynomialVerifier.Status.EQUAL,"distributive polynomial identity");
        require(PolynomialVerifier.check("x/3+x/6","x/2").status == PolynomialVerifier.Status.EQUAL,"rational polynomial identity");
        require(PolynomialVerifier.check("x^2","x").status == PolynomialVerifier.Status.NOT_EQUAL,"polynomial counterexample");
        require(PolynomialVerifier.check("x/x","1").status == PolynomialVerifier.Status.UNSUPPORTED,"partial division excluded");
        require(PolynomialVerifier.check("0/0","0").status == PolynomialVerifier.Status.UNSUPPORTED,"undefined excluded");
        require(PolynomialVerifier.check("sin(x)","sin(x)").status == PolynomialVerifier.Status.UNSUPPORTED,"functions excluded");
        require(PolynomialVerifier.check("%", "0").status == PolynomialVerifier.Status.UNSUPPORTED,"lexical input rejected");
        require(PolynomialVerifier.check("x+", "0").status == PolynomialVerifier.Status.UNSUPPORTED,"malformed expression rejected");
        require(PolynomialVerifier.check("x^(-1)","x").status == PolynomialVerifier.Status.UNSUPPORTED,"symbolic negative power excluded");
        require(PolynomialVerifier.check("(x+y)^4","0",2,1000).status == PolynomialVerifier.Status.RESOURCE_LIMIT,"term budget");
        require(PolynomialVerifier.check("(x+y)*(z+w)","0",100,1).status == PolynomialVerifier.Status.RESOURCE_LIMIT,"product budget");
        require(PolynomialVerifier.check("(2/3)^(-2)","9/4").status == PolynomialVerifier.Status.EQUAL,"constant negative power");
        require(PolynomialVerifier.check("0^0","1").status == PolynomialVerifier.Status.EQUAL,"polynomial 0^0 convention");
        // An intentionally invalid rewrite proves equality only in its chosen term theory.
        Context unsound = new Context(List.of(Rule.forward("axiom","?x","0")));
        require(unsound.checkEquivalence(p("x"),p("0")).status == Context.EquivalenceStatus.PROVED,"axiomatic derivation");
        require(PolynomialVerifier.check("x","0").status == PolynomialVerifier.Status.NOT_EQUAL,"verifier isolated from user rules");
        Random random = new Random(20261005);
        for (int i=0;i<100;i++) {
            int a=random.nextInt(21)-10, b=random.nextInt(21)-10;
            String left="("+a+"*x+"+b+")*(y+z)", right="("+a+"*x+"+b+")*y+("+a+"*x+"+b+")*z";
            require(PolynomialVerifier.check(left,right).status == PolynomialVerifier.Status.EQUAL,"generated identity " + i);
            require(PolynomialVerifier.check(left,"("+right+")+1").status == PolynomialVerifier.Status.NOT_EQUAL,"generated negative " + i);
        }
        for (FrontierVerifier.Policy policy : FrontierVerifier.Policy.values()) {
            require(FrontierVerifier.check("(x+y)^3", "x^3+3*x^2*y+3*x*y^2+y^3",1000,100000,policy).status == FrontierVerifier.Status.EQUAL, "frontier power " + policy);
            require(FrontierVerifier.check("(x+y)^3", "x^3+3*x^2*y+3*x*y^2+y^3+1",1000,100000,policy).status == FrontierVerifier.Status.NOT_EQUAL, "frontier negative " + policy);
        }
        for (String unsupported : List.of("x/x", "0*(1/0)", "sin(x)", "x^(-1)", "%", "x+"))
            require(FrontierVerifier.check(unsupported,"0").status == FrontierVerifier.Status.UNSUPPORTED, "frontier domain " + unsupported);
        require(FrontierVerifier.check("x/3+x/6","x/2").status == FrontierVerifier.Status.EQUAL,"frontier fractions");
        require(FrontierVerifier.check("0^0","1").status == FrontierVerifier.Status.EQUAL,"frontier 0^0");
        require(FrontierVerifier.check("(x+y)*(z+w)","0",100,1,FrontierVerifier.Policy.CANCELLATION).status == FrontierVerifier.Status.RESOURCE_LIMIT,"frontier preview budget");
        require(FrontierVerifier.check("x+y","0",1,1000,FrontierVerifier.Policy.CANCELLATION).status == FrontierVerifier.Status.RESOURCE_LIMIT,"frontier initial budget");
        require(FrontierVerifier.check("(u+v)*(a+b)-(w+z)*(c+d)","((u+v)-(w+z))*(c+d)+(u+v)*((a+b)-(c+d))",1000,100000,FrontierVerifier.Policy.REIFIED).status == FrontierVerifier.Status.EQUAL,"reified telescoping");
        require(FrontierVerifier.check("(x+y)^16", "(x+y)^16+1",1000,100000,FrontierVerifier.Policy.CERTIFIED).status == FrontierVerifier.Status.NOT_EQUAL,"exact point witness");
        require(FrontierVerifier.check("(x+y)^16", "(x+y)^16",1000,100000,FrontierVerifier.Policy.CERTIFIED).status == FrontierVerifier.Status.EQUAL,"point agreement does not decide identity");
        require(FrontierVerifier.check("x-y", "0",1000,100000,FrontierVerifier.Policy.CERTIFIED).status == FrontierVerifier.Status.NOT_EQUAL,"zero point falls back to exact refinement");
        require(FrontierVerifier.check("0*(1/0)", "1",1000,100000,FrontierVerifier.Policy.CERTIFIED).status == FrontierVerifier.Status.UNSUPPORTED,"point precheck preserves definedness");
        FrontierVerifier.Result guarded = FrontierVerifier.check("(x+y)^65537", "(x+y)^65537+1",1000,100000,FrontierVerifier.Policy.CERTIFIED);
        require(guarded.status == FrontierVerifier.Status.NOT_EQUAL,"oversized point evaluation falls back soundly");
        require(guarded.certificate.contains("symsat-frontier-v2"),"point bit guard uses refinement certificate");
        Random frontierRandom = new Random(1717);
        for (int i=0;i<100;i++) {
            int a=frontierRandom.nextInt(11)-5, b=frontierRandom.nextInt(11)-5;
            String left="("+a+"*x+"+b+")*(y+z)", right="("+a+"*x+"+b+")*y+("+a+"*x+"+b+")*z";
            require(FrontierVerifier.check(left,right).status == FrontierVerifier.Status.EQUAL,"frontier generated identity " + i);
            require(FrontierVerifier.check(left,"("+right+")+1").status == FrontierVerifier.Status.NOT_EQUAL,"frontier generated negative " + i);
        }
        System.out.println("PASS: " + checks + " semantic regression checks");
    }
}

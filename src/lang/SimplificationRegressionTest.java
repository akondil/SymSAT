package lang;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class SimplificationRegressionTest {
    private static int checks;
    private static Exp p(String text) throws ParseException { return Interpreter.parseString(text); }
    private static void require(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        Context context = new Context();
        SimplificationResult result = context.simplifyDetailed(p("(x*2)/2"), null);
        if (args.length == 1 && args[0].equals("--json")) {
            System.out.println(result.toJson()); return;
        }
        require(result.expression.equals(p("x")), "detailed expression");
        require(result.inputNodes == 5 && result.outputNodes == 1, "AST counts");
        require(result.cost == 1 && result.costMetric.equals("ast_size"), "size cost");
        require(result.maxIterations == context.getMaxIterations() && result.nodeLimit == context.getNodeLimit(), "budgets retained");
        require(result.saturation.enodeCount > 0 && result.saturation.eclassCount > 0, "graph size");
        require(result.insertionNanos >= 0 && result.saturationNanos >= 0 && result.extractionNanos >= 0, "monotonic durations");
        require(result.totalNanos == result.insertionNanos + result.saturationNanos + result.extractionNanos, "timing scope");
        require(context.simplify(p("(x*2)/2"), null).equals(result.expression), "compatibility API");

        Context empty = new Context(Collections.emptyList());
        SimplificationResult fixed = empty.simplifyDetailed(p("x+y"), null);
        require(fixed.isSaturated() && fixed.saturation.stopReason == EGraph.StopReason.FIXED_POINT, "fixed point");
        require(fixed.inputNodes == 3 && fixed.outputNodes == 3 && fixed.cost == 3, "unchanged AST");
        SimplificationResult operation = empty.simplifyDetailed(p("x*2"), CostFunction.OPERATION_COST);
        require(operation.costMetric.equals("operation_cost") && Math.abs(operation.cost - 3.2) < 1e-12, "operation metric");
        empty.setDefaultCost(CostFunction.OPERATION_COST);
        require(empty.simplifyDetailed(p("x*2"), null).costMetric.equals("operation_cost"), "default metric honored");
        require(empty.simplifyDetailed(p("x*2"), CostFunction.AST_SIZE).cost == 3, "explicit metric override");

        Context shortSearch = new Context(List.of(Rule.forward("first", "f(?x)", "g(?x)"),
                Rule.forward("second", "g(?x)", "h(?x)")), 1, 100);
        SimplificationResult limited = shortSearch.simplifyDetailed(p("f(x)"), null);
        require(limited.saturation.stopReason == EGraph.StopReason.ITERATION_LIMIT && !limited.isSaturated(), "iteration stop not saturation");
        require(limited.expression != null && Double.isFinite(limited.cost), "iteration limit still extracts");
        Context tiny = new Context(List.of(Rule.bidirectional("grow", "?x*(?y+?z)", "?x*?y+?x*?z")), 20, 9);
        SimplificationResult capped = tiny.simplifyDetailed(p("(a+b)*(c+d)"), null);
        require(capped.saturation.stopReason == EGraph.StopReason.NODE_LIMIT && !capped.isSaturated(), "node stop not saturation");
        require(capped.saturation.enodeCount <= capped.nodeLimit && capped.expression != null, "node limit extraction");
        Map<String, Rational> values = Map.of("a", new Rational(2), "b", new Rational(3), "c", new Rational(4), "d", new Rational(5));
        require(capped.input.accept(new EvalVisitor(values)).equals(capped.expression.accept(new EvalVisitor(values))), "bounded result preserves value");

        Context assumed = new Context(); assumed.assumeNonzero("x");
        SimplificationResult conditional = assumed.simplifyDetailed(p("x/x"), null);
        require(conditional.expression.equals(p("1")) && conditional.nonzeroAssumptions.equals(Set.of("x")), "conditional result");
        assumed.assumeNonzero("y");
        require(conditional.nonzeroAssumptions.equals(Set.of("x")), "premise snapshot");
        try { conditional.nonzeroAssumptions.add("z"); throw new AssertionError("mutable assumptions"); }
        catch (UnsupportedOperationException expected) { checks++; }
        assumed.defineVar("ratio", p("1"));
        require(assumed.simplifyDetailed(p("ratio+0"), null).input.equals(p("1+0")), "input is after let resolution");
        require(conditional.toJson().contains("\"nonzero_assumptions\":[\"x\"]"), "JSON premises");
        require(capped.toJson().contains("\"saturated\":false"), "JSON bounded search");
        require(fixed.toJson().contains("\"format\":\"symsat-simplification-v1\""), "JSON format version");
        require(fixed.summary().contains("stop=FIXED_POINT"), "human summary");
        SimplificationResult escaped = empty.simplifyDetailed(new Symbol("a\"\\\n\t"), null);
        require(escaped.toJson().contains("a\\\"\\\\\\n\\t"), "JSON string escaping");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream stdout = System.out;
        try (PrintStream captured = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            List<Object> outputs = Interpreter.executeProgram("simplify x+y;", shortSearch);
            require(outputs.get(0) instanceof Exp, "DSL return value compatibility");
            List<Object> operationOutputs = Interpreter.executeProgram("simplify x*2;", empty);
            require(operationOutputs.get(0).equals(p("x*2")), "DSL default cost compatibility");
        } finally { System.setOut(stdout); }
        require(bytes.toString(StandardCharsets.UTF_8).contains("[Search] stop=FIXED_POINT"), "CLI exposes stop reason");
        require(bytes.toString(StandardCharsets.UTF_8).contains("cost(operation_cost)"), "CLI exposes selected default metric");
        try {
            new SimplifyStmt(p("x"), "unknown_metric").execute(empty);
            throw new AssertionError("unknown metric silently accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        System.out.println("PASS: " + checks + " simplification result checks");
    }
}

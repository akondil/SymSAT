package lang;

import java.util.*;

/** Checks the exact lower-bound shortcut without relying on noisy runtime timings. */
public final class CostBoundRegressionTest {
    private static int checks;
    private static Exp p(String text) throws ParseException { return Interpreter.parseString(text); }
    private static void require(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        boolean export = args.length == 1 && args[0].equals("--comparison");
        List<String> rows = new ArrayList<>();
        for (String[] sample : new String[][] {
                {"(x*2)/2", "x"}, {"((x*4)/2)/2", "x"},
                {"(sin(t)^2+cos(t)^2)*5", "5"}}) {
            for (CostFunction metric : List.of(CostFunction.AST_SIZE, CostFunction.OPERATION_COST)) {
                List<Rule> rules = Interpreter.getDefaultEquivalenceRules();
                Exp input = p(sample[0]);
                // Delegation preserves the metric exactly while explicitly disabling built-in recognition.
                CostFunction sameMetricWithoutShortcut = (node, children) -> metric.cost(node, children);
                SimplificationResult baseline = Interpreter.simplifyWithResult(input, rules,
                        sameMetricWithoutShortcut, 10, 5000, Collections.emptySet());
                SimplificationResult optimized = Interpreter.simplifyWithResult(input, rules,
                        metric, 10, 5000, Collections.emptySet());
                require(optimized.expression.equals(p(sample[1])), "expected representative");
                require(optimized.expression.equals(baseline.expression), "output unchanged");
                require(optimized.cost == baseline.cost, "cost unchanged");
                require(optimized.reachedCostLowerBound() && !optimized.isSaturated(), "lower bound distinguished from saturation");
                require(optimized.saturation.enodeCount < baseline.saturation.enodeCount, "graph reduction");
                require(optimized.saturation.iterations < baseline.saturation.iterations, "iteration reduction");
                require(!baseline.reachedCostLowerBound(), "custom cost disables shortcut");
                require(optimized.toJson().contains("\"cost_lower_bound_reached\":true"), "JSON stop semantics");
                rows.add("{\"metric\":\"" + optimized.costMetric + "\",\"baseline\":"
                        + baseline.toJson() + ",\"optimized\":" + optimized.toJson() + "}");
            }
        }
        if (export) {
            System.out.println("{\"format\":\"symsat-cost-bound-comparison-v1\","
                    + "\"scope\":\"three demonstrations; no comparative speed claim\","
                    + "\"rows\":[" + String.join(",", rows) + "]}");
            return;
        }

        Context context = new Context();
        for (String leaf : List.of("x", "0", "2")) {
            SimplificationResult result = context.simplifyDetailed(p(leaf), null);
            require(result.reachedCostLowerBound() && result.saturation.iterations == 0, "initial minimum leaf");
        }
        SimplificationResult unguarded = context.simplifyDetailed(p("x/x"), null);
        require(!unguarded.reachedCostLowerBound() && unguarded.expression.equals(p("x/x")), "no unsafe shortcut");
        context.assumeNonzero("x");
        SimplificationResult guarded = context.simplifyDetailed(p("x/x"), null);
        require(guarded.reachedCostLowerBound() && guarded.nonzeroAssumptions.equals(Set.of("x")), "conditional lower bound");
        require(context.checkEquivalence(p("x/x"), p("1")).saturation.stopReason == EGraph.StopReason.EQUIVALENCE_FOUND,
                "proof stopping policy preserved");

        CostFunction custom = (node, children) -> {
            double cost = node.isConst() && node.getConstVal().equals(new Rational(2)) ? 10 : 0.1;
            for (double child : children) cost += child;
            return cost;
        };
        Context customContext = new Context(List.of(Rule.forward("alternative", "2", "f(1)")));
        SimplificationResult customResult = customContext.simplifyDetailed(p("2"), custom);
        require(customResult.expression.equals(p("f(1)")) && Math.abs(customResult.cost - 0.2) < 1e-12,
                "custom metric can prefer a non-leaf");
        require(customResult.isSaturated(), "custom search reaches its own fixed point");

        EGraph full = new EGraph(); full.add(p("(x*2)/2"));
        EGraph.SaturationResult ordinary = full.saturateWithResult(Interpreter.getDefaultEquivalenceRules(), 10, 5000);
        require(ordinary.stopReason == EGraph.StopReason.ITERATION_LIMIT, "ordinary saturation API unchanged");
        Context domain = new Context();
        require(!domain.simplifyDetailed(p("0*(1/0)"), null).reachedCostLowerBound(), "undefined expression not given a minimum leaf");
        System.out.println("PASS: " + checks + " cost lower-bound checks");
    }
}

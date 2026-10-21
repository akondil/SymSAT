package lang;

import java.util.*;

/** Immutable observation of one bounded search; a resource stop is not saturation.
 * Costs describe the extracted AST under the selected metric, not a global optimum.
 */
public final class SimplificationResult {
    public final Exp input;
    public final Exp expression;
    public final EGraph.SaturationResult saturation;
    public final Set<String> nonzeroAssumptions;
    public final String costMetric;
    public final double cost;
    public final int inputNodes, outputNodes, maxIterations, nodeLimit;
    public final SearchLimits limits;
    public final long insertionNanos, saturationNanos, extractionNanos, totalNanos;

    SimplificationResult(Exp input, Exp expression, EGraph.SaturationResult saturation,
                         Set<String> assumptions, CostFunction metric, double cost,
                         SearchLimits limits,
                         long insertionNanos, long saturationNanos, long extractionNanos) {
        this.input = input;
        this.expression = expression;
        this.saturation = saturation;
        this.nonzeroAssumptions = Collections.unmodifiableSet(new TreeSet<>(assumptions));
        this.costMetric = metric == CostFunction.AST_SIZE ? "ast_size"
                : metric == CostFunction.OPERATION_COST ? "operation_cost" : "custom";
        if (!Double.isFinite(cost)) throw new IllegalStateException("Extracted cost is not finite");
        this.cost = cost;
        this.inputNodes = countNodes(input);
        this.outputNodes = countNodes(expression);
        this.limits = limits;
        this.maxIterations = limits.maxIterations;
        this.nodeLimit = limits.nodeLimit;
        this.insertionNanos = insertionNanos;
        this.saturationNanos = saturationNanos;
        this.extractionNanos = extractionNanos;
        this.totalNanos = insertionNanos + saturationNanos + extractionNanos;
    }

    public boolean isSaturated() { return saturation.stopReason == EGraph.StopReason.FIXED_POINT; }

    public boolean reachedCostLowerBound() { return saturation.stopReason == EGraph.StopReason.COST_LOWER_BOUND; }

    /** AST tree count: repeated subexpressions are counted per occurrence, not as a DAG. */
    private static int countNodes(Exp expression) {
        return expression.accept(new Visitor<Integer>() {
            public Integer visit(Const e) { return 1; }
            public Integer visit(Symbol e) { return 1; }
            private int pair(Exp a, Exp b) { return 1 + a.accept(this) + b.accept(this); }
            public Integer visit(Plus e) { return pair(e.a, e.b); }
            public Integer visit(Minus e) { return pair(e.a, e.b); }
            public Integer visit(Multiply e) { return pair(e.a, e.b); }
            public Integer visit(Divide e) { return pair(e.a, e.b); }
            public Integer visit(Power e) { return pair(e.base, e.exp); }
            public Integer visit(UnaryMinus e) { return 1 + e.a.accept(this); }
            public Integer visit(FunctionCall e) {
                int count = 1;
                for (Exp argument : e.args) count += argument.accept(this);
                return count;
            }
        });
    }

    public String summary() {
        return "stop=" + saturation.stopReason + ", iterations=" + saturation.iterations
                + ", cost(" + costMetric + ")=" + cost + ", AST=" + inputNodes + " -> " + outputNodes
                + ", e-classes=" + saturation.eclassCount + ", e-nodes=" + saturation.enodeCount
                + ", work=" + saturation.workUnits + ", bindings=" + saturation.bindingRecords
                + ", engine-ns=" + totalNanos;
    }

    /** Versioned JSON suitable for saving raw observations; no stdout or file side effects. */
    public String toJson() {
        List<String> assumptions = new ArrayList<>();
        for (String symbol : nonzeroAssumptions) assumptions.add(quote(symbol));
        return "{\"format\":\"symsat-simplification-v1\",\"input\":" + quote(input.toString())
                + ",\"expression\":" + quote(expression.toString())
                + ",\"nonzero_assumptions\":[" + String.join(",", assumptions) + "]"
                + ",\"cost_metric\":" + quote(costMetric) + ",\"cost\":" + cost
                + ",\"input_nodes\":" + inputNodes + ",\"output_nodes\":" + outputNodes
                + ",\"stop_reason\":" + quote(saturation.stopReason.name())
                + ",\"saturated\":" + isSaturated() + ",\"iterations\":" + saturation.iterations
                + ",\"cost_lower_bound_reached\":" + reachedCostLowerBound()
                + ",\"eclasses\":" + saturation.eclassCount + ",\"enodes\":" + saturation.enodeCount
                + ",\"max_iterations\":" + maxIterations + ",\"node_limit\":" + nodeLimit
                + ",\"work_units\":" + saturation.workUnits + ",\"binding_records\":" + saturation.bindingRecords
                + ",\"max_work\":" + limits.maxWork + ",\"max_bindings\":" + limits.maxBindings
                + ",\"max_integer_bits\":" + limits.maxIntegerBits + ",\"max_depth\":" + limits.maxDepth
                + ",\"max_input_nodes\":" + limits.maxInputNodes
                + ",\"timing_ns\":{\"insertion\":" + insertionNanos
                + ",\"saturation\":" + saturationNanos + ",\"extraction\":" + extractionNanos
                + ",\"total\":" + totalNanos + "}}";
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (c < 32) escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else escaped.append(c);
            }
        }
        return escaped.append('"').toString();
    }
}

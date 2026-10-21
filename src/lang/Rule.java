package lang;

import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/**
 * Declarative Rewrite Rule for Symbolic Mathematics.
 * Can be unidirectional (left -> right) or bidirectional (left <=> right).
 */
public class Rule {
    public enum Condition { DEFINED, NONZERO }
    public final String name;
    public final Exp left;
    public final Exp right;
    public final boolean isBidirectional;
    public final String conditionVariable;
    public final Condition condition;

    public Rule(String name, Exp left, Exp right, boolean isBidirectional) {
        this(name, left, right, isBidirectional, null, null);
    }

    private Rule(String name, Exp left, Exp right, boolean isBidirectional,
                 String conditionVariable, Condition condition) {
        this.name = Objects.requireNonNull(name);
        this.left = Objects.requireNonNull(left);
        this.right = Objects.requireNonNull(right);
        this.isBidirectional = isBidirectional;
        this.conditionVariable = conditionVariable;
        this.condition = condition;
        Set<String> lhs = variables(left), rhs = variables(right);
        if (!lhs.containsAll(rhs) || (isBidirectional && !rhs.containsAll(lhs))) {
            throw new IllegalArgumentException("Unbound pattern variable in rule '" + name + "'");
        }
        if (condition != null && !lhs.contains(conditionVariable)) {
            throw new IllegalArgumentException("Unbound condition variable in rule '" + name + "'");
        }
    }

    /** Programmatic guard; DSL user equations remain explicit, trusted axioms. */
    public static Rule forwardWhen(String name, String left, String right,
                                   String variable, Condition condition) {
        Objects.requireNonNull(variable, "variable");
        Objects.requireNonNull(condition, "condition");
        try {
            return new Rule(name, Interpreter.parseString(left), Interpreter.parseString(right),
                    false, variable, condition);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Failed to parse rule '" + name + "'", e);
        }
    }

    boolean permits(Map<String, Integer> bindings, DomainAnalysis facts) {
        if (condition == null) return true;
        Integer id = bindings.get(conditionVariable);
        return id != null && (condition == Condition.DEFINED ? facts.isDefined(id) : facts.isNonzero(id));
    }

    private boolean permits(Map<String, Exp> bindings) {
        if (condition == null) return true;
        EGraph graph = new EGraph();
        Map<String, Integer> ids = new java.util.HashMap<>();
        for (Map.Entry<String, Exp> entry : bindings.entrySet()) ids.put(entry.getKey(), graph.add(entry.getValue()));
        graph.saturateWithResult(java.util.Collections.emptyList(), Context.DEFAULT_MAX_ITERATIONS,
                Context.DEFAULT_NODE_LIMIT);
        return permits(ids, new DomainAnalysis(graph));
    }

    private static Set<String> variables(Exp expression) {
        Set<String> result = new HashSet<>();
        expression.accept(new Visitor<Void>() {
            public Void visit(Const e) { return null; }
            public Void visit(Symbol e) { if (e.isPatternVariable()) result.add(e.name); return null; }
            private Void pair(Exp a, Exp b) { a.accept(this); b.accept(this); return null; }
            public Void visit(Plus e) { return pair(e.a, e.b); }
            public Void visit(Minus e) { return pair(e.a, e.b); }
            public Void visit(Multiply e) { return pair(e.a, e.b); }
            public Void visit(Divide e) { return pair(e.a, e.b); }
            public Void visit(Power e) { return pair(e.base, e.exp); }
            public Void visit(UnaryMinus e) { e.a.accept(this); return null; }
            public Void visit(FunctionCall e) { for (Exp a : e.args) a.accept(this); return null; }
        });
        return result;
    }

    public static Rule forward(String name, String leftPattern, String rightTemplate) {
        try {
            Exp l = Interpreter.parseString(leftPattern);
            Exp r = Interpreter.parseString(rightTemplate);
            return new Rule(name, l, r, false);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Failed to parse rule '" + name + "': " + e.getMessage(), e);
        }
    }

    public static Rule bidirectional(String name, String leftPattern, String rightPattern) {
        try {
            Exp l = Interpreter.parseString(leftPattern);
            Exp r = Interpreter.parseString(rightPattern);
            return new Rule(name, l, r, true);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Failed to parse rule '" + name + "': " + e.getMessage(), e);
        }
    }

    /**
     * Tries to apply the rule left -> right on the target expression.
     * Returns the rewritten expression, or null if it does not match.
     */
    public Exp applyForward(Exp target) {
        Map<String, Exp> bindings = PatternMatcher.match(left, target);
        if (bindings != null && permits(bindings)) {
            return PatternMatcher.instantiate(right, bindings);
        }
        return null;
    }

    /**
     * Tries to apply the rule right -> left on the target expression (if bidirectional).
     */
    public Exp applyBackward(Exp target) {
        if (!isBidirectional) return null;
        Map<String, Exp> bindings = PatternMatcher.match(right, target);
        if (bindings != null && permits(bindings)) {
            return PatternMatcher.instantiate(left, bindings);
        }
        return null;
    }

    @Override
    public String toString() {
        return name + ": " + left + (isBidirectional ? " <=> " : " => ") + right
                + (condition == null ? "" : " [" + condition + " " + conditionVariable + "]");
    }
}

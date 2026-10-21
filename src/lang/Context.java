package lang;

import java.util.*;

/**
 * Runtime Environment and State for SymSAT programs.
 * Manages defined variables, declarative rewrite rules, and e-graph configuration.
 */
public class Context {
    public static final int DEFAULT_MAX_ITERATIONS = 10;
    public static final int DEFAULT_NODE_LIMIT = 5000;

    public enum EquivalenceStatus {
        PROVED,
        NOT_DERIVED,
        INCOMPLETE
    }

    public static final class EquivalenceResult {
        public final EquivalenceStatus status;
        public final EGraph.SaturationResult saturation;
        public final Set<String> nonzeroAssumptions;

        private EquivalenceResult(EquivalenceStatus status, EGraph.SaturationResult saturation,
                                  Set<String> nonzeroAssumptions) {
            this.status = status;
            this.saturation = saturation;
            this.nonzeroAssumptions = Collections.unmodifiableSet(new TreeSet<>(nonzeroAssumptions));
        }
    }

    private final Map<String, Exp> variables = new HashMap<>();
    private final List<Rule> rules = new ArrayList<>();
    private final Set<String> nonzeroAssumptions = new TreeSet<>();
    private CostFunction defaultCost = CostFunction.AST_SIZE;
    private final int maxIterations;
    private final int nodeLimit;
    private final SearchLimits searchLimits;

    public Context() {
        this(Interpreter.getDefaultEquivalenceRules(), DEFAULT_MAX_ITERATIONS, DEFAULT_NODE_LIMIT);
    }

    /**
     * Creates a context with exactly the supplied initial rewrite rules and
     * the standard search limits. Pass an empty list to use no rewrite rules.
     */
    public Context(List<Rule> initialRules) {
        this(initialRules, DEFAULT_MAX_ITERATIONS, DEFAULT_NODE_LIMIT);
    }

    /**
     * Creates a context with an explicit rewrite theory and saturation limits.
     * E-graph commutative handling and constant folding remain controlled by
     * the EGraph defaults; the supplied list replaces the Context's default
     * rewrite-rule set.
     */
    public Context(List<Rule> initialRules, int maxIterations, int nodeLimit) {
        this(initialRules, new SearchLimits(maxIterations, nodeLimit));
    }

    public Context(List<Rule> initialRules, SearchLimits limits) {
        Objects.requireNonNull(initialRules, "initialRules");
        this.searchLimits = Objects.requireNonNull(limits);
        for (Rule rule : initialRules) rules.add(Objects.requireNonNull(rule, "rule"));
        this.maxIterations = limits.maxIterations;
        this.nodeLimit = limits.nodeLimit;
    }

    public SearchLimits getSearchLimits() { return searchLimits; }

    public void defineVar(String name, Exp val) {
        searchLimits.validateInput(val);
        if (nonzeroAssumptions.contains(name))
            throw new IllegalArgumentException("Cannot let-bind assumed symbol '" + name
                    + "'; use eval ... where for values");
        variables.put(name, val);
    }

    /** Assumptions constrain unbound real symbols for the lifetime of this context.
     * Assumed symbols cannot be let-bound, avoiding stale conditional simplifications.
     */
    public void assumeNonzero(String name) {
        Objects.requireNonNull(name, "name");
        if (!name.matches("[a-zA-Z][a-zA-Z0-9_]*"))
            throw new IllegalArgumentException("Assumptions require an ordinary symbol name");
        if (variables.containsKey(name))
            throw new IllegalArgumentException("Assumptions require an unbound symbol: " + name);
        nonzeroAssumptions.add(name);
    }

    public Set<String> getNonzeroAssumptions() {
        return Collections.unmodifiableSet(new TreeSet<>(nonzeroAssumptions));
    }

    public String assumptionSummary() {
        List<String> conditions = new ArrayList<>();
        for (String symbol : nonzeroAssumptions) conditions.add(symbol + " != 0");
        return String.join(", ", conditions);
    }

    public Rational evaluate(Exp expression, Map<String, Rational> bindings) {
        searchLimits.validateInput(expression);
        Objects.requireNonNull(bindings, "bindings");
        for (String symbol : nonzeroAssumptions) {
            Rational value = bindings.get(symbol);
            if (value != null && value.isZero())
                throw new IllegalArgumentException("Evaluation violates assumption: " + symbol + " != 0");
        }
        Exp resolved = resolve(expression);
        searchLimits.validateInput(resolved);
        return resolved.accept(new EvalVisitor(bindings, searchLimits));
    }

    public Exp getVar(String name) {
        return variables.get(name);
    }

    public boolean hasVar(String name) {
        return variables.containsKey(name);
    }

    public void addRule(Rule r) {
        rules.add(Objects.requireNonNull(r, "rule"));
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public int getNodeLimit() {
        return nodeLimit;
    }

    public List<Rule> getRules() {
        return Collections.unmodifiableList(rules);
    }

    public void setDefaultCost(CostFunction cost) {
        this.defaultCost = cost;
    }

    public CostFunction getDefaultCost() {
        return defaultCost;
    }

    /**
     * Resolves all let-bound variables within an expression.
     */
    public Exp resolve(Exp expr) {
        if (variables.isEmpty()) return expr;
        return expr.accept(new SubstVisitor(variables));
    }

    /**
     * Simplifies an expression using Equality Saturation with currently active rules.
     */
    public Exp simplify(Exp expr, CostFunction costFn) {
        return simplifyDetailed(expr, costFn).expression;
    }

    public SimplificationResult simplifyDetailed(Exp expr, CostFunction costFn) {
        searchLimits.validateInput(expr);
        Exp resolved = resolve(expr);
        CostFunction cf = (costFn != null) ? costFn : defaultCost;
        return Interpreter.simplifyWithResult(resolved, rules, cf, searchLimits, nonzeroAssumptions);
    }

    /**
     * Differentiates an expression with respect to var and simplifies it.
     */
    public Exp differentiate(Exp expr, String targetVar) {
        searchLimits.validateInput(expr);
        Exp resolved = resolve(expr);
        searchLimits.validateInput(resolved);
        Exp dRaw = resolved.accept(new DiffVisitor(targetVar));
        return simplify(dRaw, defaultCost);
    }

    /**
     * Checks whether expr1 and expr2 become equal under the active rewrite rules.
     * A negative result after fixed-point saturation means only that equality was not
     * derived; an iteration or node limit produces INCOMPLETE rather than inequivalence.
     * Positive queries stop as soon as a completed saturation iteration merges the roots.
     */
    public EquivalenceResult checkEquivalence(Exp expr1, Exp expr2) {
        EGraph egraph = new EGraph(nonzeroAssumptions, searchLimits);
        try {
        searchLimits.validateInput(expr1);
        searchLimits.validateInput(expr2);
        Exp r1 = resolve(expr1);
        Exp r2 = resolve(expr2);
        int id1 = egraph.add(r1);
        int id2 = egraph.add(r2);
        EGraph.SaturationResult saturation = egraph.saturateUntilEquivalent(
            rules, maxIterations, nodeLimit, id1, id2);

        if (egraph.find(id1) == egraph.find(id2)) {
            return new EquivalenceResult(EquivalenceStatus.PROVED, saturation, nonzeroAssumptions);
        }
        if (saturation.stopReason == EGraph.StopReason.FIXED_POINT) {
            return new EquivalenceResult(EquivalenceStatus.NOT_DERIVED, saturation, nonzeroAssumptions);
        }
        return new EquivalenceResult(EquivalenceStatus.INCOMPLETE, saturation, nonzeroAssumptions);
        } catch (SearchLimitException exceeded) {
            return new EquivalenceResult(EquivalenceStatus.INCOMPLETE,
                    egraph.inputStopped(exceeded.reason), nonzeroAssumptions);
        }
    }

    /**
     * Compatibility helper. False includes both non-derivation and incomplete search;
     * callers that need the distinction should use {@link #checkEquivalence(Exp, Exp)}.
     */
    public boolean proveEquivalence(Exp expr1, Exp expr2) {
        return checkEquivalence(expr1, expr2).status == EquivalenceStatus.PROVED;
    }
}

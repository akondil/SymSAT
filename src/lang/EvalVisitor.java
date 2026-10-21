package lang;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Numeric / Environment evaluation visitor.
 * Evaluates expressions given variable bindings (e.g. x = 2).
 */
public class EvalVisitor implements Visitor<Rational> {
    private final Map<String, Rational> env;
    private final SearchLimits limits;
    private final SearchBudget budget;

    public EvalVisitor() {
        this(Collections.emptyMap());
    }

    public EvalVisitor(Map<String, Rational> env) {
        this(env, SearchLimits.defaults());
    }

    public EvalVisitor(Map<String, Rational> env, SearchLimits limits) {
        this.env = new HashMap<>(env);
        this.limits = java.util.Objects.requireNonNull(limits);
        this.budget = new SearchBudget(limits);
        for (Rational value : this.env.values()) limits.checkRational(value);
    }

    @Override
    public Rational visit(Const e) {
        budget.work(); limits.checkRational(e.val);
        return e.val;
    }

    @Override
    public Rational visit(Symbol e) {
        budget.work();
        if (!env.containsKey(e.name)) {
            throw new RuntimeException("Unbound variable during numerical evaluation: " + e.name);
        }
        return env.get(e.name);
    }

    @Override
    public Rational visit(Plus e) {
        budget.work();
        Rational a=e.a.accept(this), b=e.b.accept(this);
        limits.checkFold("+", java.util.List.of(a,b));
        return a.add(b);
    }

    @Override
    public Rational visit(Minus e) {
        budget.work();
        Rational a=e.a.accept(this), b=e.b.accept(this);
        limits.checkFold("-", java.util.List.of(a,b));
        return a.subtract(b);
    }

    @Override
    public Rational visit(Multiply e) {
        budget.work();
        Rational a=e.a.accept(this), b=e.b.accept(this);
        limits.checkFold("*", java.util.List.of(a,b));
        return a.multiply(b);
    }

    @Override
    public Rational visit(Divide e) {
        budget.work();
        Rational a=e.a.accept(this), b=e.b.accept(this);
        limits.checkFold("/", java.util.List.of(a,b));
        return a.divide(b);
    }

    @Override
    public Rational visit(Power e) {
        budget.work();
        Rational base = e.base.accept(this);
        Rational exp = e.exp.accept(this);
        if (!exp.isInteger()) {
            throw new UnsupportedOperationException("Non-integer rational exponents not yet supported in exact Rational domain: " + exp);
        }
        limits.checkFold("^", java.util.List.of(base,exp));
        return base.pow(exp.getNum());
    }

    @Override
    public Rational visit(UnaryMinus e) {
        budget.work();
        return e.a.accept(this).negate();
    }

    @Override
    public Rational visit(FunctionCall e) {
        budget.work();
        throw new UnsupportedOperationException("Transcendental functions (sin, cos, ln, exp) require floating-point or symbolic evaluation: " + e.name);
    }
}

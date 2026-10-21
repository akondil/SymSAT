package lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Bottom-Up Algebraic Simplification Visitor (Greedy Term Rewriting).
 * Implements algebraic identity reduction and constant folding.
 */
public class SimplifyVisitor implements Visitor<Exp> {

    public Exp simplify(Exp e) {
        // Run until fixed-point (no changes) or max iterations to avoid potential cycles
        Exp current = e;
        for (int i = 0; i < 20; i++) {
            Exp next = current.accept(this);
            if (next.equals(current)) {
                break;
            }
            current = next;
        }
        return current;
    }

    @Override
    public Exp visit(Const e) {
        return e;
    }

    @Override
    public Exp visit(Symbol e) {
        return e;
    }

    @Override
    public Exp visit(Plus e) {
        Exp a = e.a.accept(this);
        Exp b = e.b.accept(this);

        // Constant folding
        if (a instanceof Const && b instanceof Const) {
            return new Const(((Const) a).val.add(((Const) b).val));
        }

        // 0 + b -> b
        if (a instanceof Const && ((Const) a).val.isZero()) {
            return b;
        }
        // a + 0 -> a
        if (b instanceof Const && ((Const) b).val.isZero()) {
            return a;
        }

        // a + a -> 2 * a
        if (a.equals(b)) {
            return new Multiply(new Const(2), a);
        }

        // a + (-b) -> a - b
        if (b instanceof UnaryMinus) {
            return new Minus(a, ((UnaryMinus) b).a);
        }
        // (-a) + b -> b - a
        if (a instanceof UnaryMinus) {
            return new Minus(b, ((UnaryMinus) a).a);
        }

        return new Plus(a, b);
    }

    @Override
    public Exp visit(Minus e) {
        Exp a = e.a.accept(this);
        Exp b = e.b.accept(this);

        // Constant folding
        if (a instanceof Const && b instanceof Const) {
            return new Const(((Const) a).val.subtract(((Const) b).val));
        }

        // a - 0 -> a
        if (b instanceof Const && ((Const) b).val.isZero()) {
            return a;
        }
        // 0 - b -> -b
        if (a instanceof Const && ((Const) a).val.isZero()) {
            return new UnaryMinus(b).accept(this);
        }

        // a - a -> 0
        if (a.equals(b) && DomainAnalysis.isDefined(a)) {
            return new Const(0);
        }

        // a - (-b) -> a + b
        if (b instanceof UnaryMinus) {
            return new Plus(a, ((UnaryMinus) b).a).accept(this);
        }

        return new Minus(a, b);
    }

    @Override
    public Exp visit(Multiply e) {
        Exp a = e.a.accept(this);
        Exp b = e.b.accept(this);

        // Constant folding
        if (a instanceof Const && b instanceof Const) {
            return new Const(((Const) a).val.multiply(((Const) b).val));
        }

        // 0 * b -> 0 or a * 0 -> 0
        if (a instanceof Const && ((Const) a).val.isZero() && DomainAnalysis.isDefined(b)) return new Const(0);
        if (b instanceof Const && ((Const) b).val.isZero() && DomainAnalysis.isDefined(a)) return new Const(0);

        // 1 * b -> b
        if (a instanceof Const && ((Const) a).val.isOne()) return b;
        // a * 1 -> a
        if (b instanceof Const && ((Const) b).val.isOne()) return a;

        // -1 * b -> -b
        if (a instanceof Const && ((Const) a).val.isMinusOne()) return new UnaryMinus(b).accept(this);
        // a * -1 -> -a
        if (b instanceof Const && ((Const) b).val.isMinusOne()) return new UnaryMinus(a).accept(this);

        // a * a -> a ^ 2
        if (a.equals(b)) {
            return new Power(a, new Const(2));
        }

        return new Multiply(a, b);
    }

    @Override
    public Exp visit(Divide e) {
        Exp a = e.a.accept(this);
        Exp b = e.b.accept(this);

        // Constant folding
        if (a instanceof Const && b instanceof Const) {
            return new Const(((Const) a).val.divide(((Const) b).val));
        }

        // 0 / b -> 0
        if (a instanceof Const && ((Const) a).val.isZero() && DomainAnalysis.isNonzero(b)) {
            return new Const(0);
        }

        // a / 1 -> a
        if (b instanceof Const && ((Const) b).val.isOne()) {
            return a;
        }

        // a / a -> 1
        if (a.equals(b) && DomainAnalysis.isNonzero(a)) {
            return new Const(1);
        }

        return new Divide(a, b);
    }

    @Override
    public Exp visit(Power e) {
        Exp base = e.base.accept(this);
        Exp exp = e.exp.accept(this);

        // base ^ 0 -> 1
        if (exp instanceof Const && ((Const) exp).val.isZero() && DomainAnalysis.isDefined(base)) {
            return new Const(1);
        }

        // base ^ 1 -> base
        if (exp instanceof Const && ((Const) exp).val.isOne()) {
            return base;
        }

        // 0 ^ exp -> 0
        if (base instanceof Const && ((Const) base).val.isZero()
                && exp instanceof Const && ((Const) exp).val.isInteger()
                && ((Const) exp).val.compareTo(Rational.ZERO) > 0) {
            return new Const(0);
        }

        // 1 ^ exp -> 1
        if (base instanceof Const && ((Const) base).val.isOne() && DomainAnalysis.isDefined(exp)) {
            return new Const(1);
        }

        // Constant folding for integer exponents
        if (base instanceof Const && exp instanceof Const) {
            Rational bVal = ((Const) base).val;
            Rational eVal = ((Const) exp).val;
            if (eVal.isInteger()
                    && eVal.getNum().abs().compareTo(java.math.BigInteger.valueOf(100)) <= 0) {
                return new Const(bVal.pow(eVal.getNum()));
            }
        }

        return new Power(base, exp);
    }

    @Override
    public Exp visit(UnaryMinus e) {
        Exp a = e.a.accept(this);

        // - (Const c) -> Const(-c)
        if (a instanceof Const) {
            return new Const(((Const) a).val.negate());
        }

        // - (-u) -> u
        if (a instanceof UnaryMinus) {
            return ((UnaryMinus) a).a;
        }

        return new UnaryMinus(a);
    }

    @Override
    public Exp visit(FunctionCall e) {
        List<Exp> newArgs = new ArrayList<>();
        for (Exp arg : e.args) {
            newArgs.add(arg.accept(this));
        }

        // Function constant evaluations:
        if (newArgs.size() == 1 && newArgs.get(0) instanceof Const) {
            Rational val = ((Const) newArgs.get(0)).val;
            String name = e.name.toLowerCase();
            if (name.equals("sin") && val.isZero()) return new Const(0);
            if (name.equals("cos") && val.isZero()) return new Const(1);
            if (name.equals("ln") && val.isOne()) return new Const(0);
            if (name.equals("exp") && val.isZero()) return new Const(1);
        }

        return new FunctionCall(e.name, newArgs);
    }
}

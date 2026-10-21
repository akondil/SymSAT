package lang;

import java.util.Objects;

/**
 * Analytical Symbolic Differentiation Visitor: d(f)/d(var)
 */
public class DiffVisitor implements Visitor<Exp> {
    private final String var;

    public DiffVisitor(String var) {
        this.var = Objects.requireNonNull(var);
    }

    @Override
    public Exp visit(Const e) {
        // d(c)/dx = 0
        return new Const(0);
    }

    @Override
    public Exp visit(Symbol e) {
        // d(x)/dx = 1, d(y)/dx = 0
        if (e.name.equals(var)) {
            return new Const(1);
        }
        return new Const(0);
    }

    @Override
    public Exp visit(Plus e) {
        // (u + v)' = u' + v'
        return new Plus(e.a.accept(this), e.b.accept(this));
    }

    @Override
    public Exp visit(Minus e) {
        // (u - v)' = u' - v'
        return new Minus(e.a.accept(this), e.b.accept(this));
    }

    @Override
    public Exp visit(Multiply e) {
        // (u * v)' = u' * v + u * v'
        Exp du = e.a.accept(this);
        Exp dv = e.b.accept(this);
        return new Plus(new Multiply(du, e.b), new Multiply(e.a, dv));
    }

    @Override
    public Exp visit(Divide e) {
        // (u / v)' = (u' * v - u * v') / (v ^ 2)
        Exp du = e.a.accept(this);
        Exp dv = e.b.accept(this);
        Exp numerator = new Minus(new Multiply(du, e.b), new Multiply(e.a, dv));
        Exp denominator = new Power(e.b, new Const(2));
        return new Divide(numerator, denominator);
    }

    @Override
    public Exp visit(Power e) {
        // If exponent is a constant n: d(u^n)/dx = n * u^(n-1) * u'
        if (e.exp instanceof Const) {
            Const n = (Const) e.exp;
            Exp du = e.base.accept(this);
            Rational nMinusOne = n.val.subtract(Rational.ONE);
            Exp powerPart = new Power(e.base, new Const(nMinusOne));
            return new Multiply(new Multiply(n, powerPart), du);
        }

        // General case: d(u^v)/dx = u^v * (v' * ln(u) + v * u' / u)
        Exp du = e.base.accept(this);
        Exp dv = e.exp.accept(this);
        Exp term1 = new Multiply(dv, new FunctionCall("ln", e.base));
        Exp term2 = new Divide(new Multiply(e.exp, du), e.base);
        return new Multiply(e, new Plus(term1, term2));
    }

    @Override
    public Exp visit(UnaryMinus e) {
        // (-u)' = -u'
        return new UnaryMinus(e.a.accept(this));
    }

    @Override
    public Exp visit(FunctionCall e) {
        if (e.args.size() != 1) {
            throw new UnsupportedOperationException("Differentiation requires a unary supported function: " + e.name);
        }
        Exp u = e.args.get(0);
        Exp du = u.accept(this);

        String func = e.name.toLowerCase();
        switch (func) {
            case "sin":
                // d(sin(u))/dx = cos(u) * u'
                return new Multiply(new FunctionCall("cos", u), du);
            case "cos":
                // d(cos(u))/dx = -sin(u) * u'
                return new Multiply(new UnaryMinus(new FunctionCall("sin", u)), du);
            case "ln":
                // d(ln(u))/dx = u' / u
                return new Divide(du, u);
            case "exp":
                // d(exp(u))/dx = exp(u) * u'
                return new Multiply(new FunctionCall("exp", u), du);
            default:
                throw new UnsupportedOperationException("Symbolic derivative not defined for function: " + e.name);
        }
    }
}

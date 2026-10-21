package lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Base class for all Symbolic Expressions (Abstract Syntax Tree).
 */
abstract class Exp {
    public abstract <T> T accept(Visitor<T> v);

    @Override
    public abstract boolean equals(Object o);

    @Override
    public abstract int hashCode();

    @Override
    public abstract String toString();
}

/**
 * Exact Numerical Constant (Rational number).
 */
class Const extends Exp {
    public final Rational val;

    public Const(long n) {
        this(new Rational(n));
    }

    public Const(Rational val) {
        this.val = Objects.requireNonNull(val);
    }

    @Override
    public <T> T accept(Visitor<T> v) {
        return v.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Const aConst = (Const) o;
        return val.equals(aConst.val);
    }

    @Override
    public int hashCode() {
        return Objects.hash(val);
    }

    @Override
    public String toString() {
        return val.toString();
    }
}

/**
 * Symbolic Variable (e.g. x, y, theta) or Pattern Variable (e.g. ?x).
 */
class Symbol extends Exp {
    public final String name;

    public Symbol(String name) {
        this.name = Objects.requireNonNull(name);
    }

    public boolean isPatternVariable() {
        return name.startsWith("?");
    }

    @Override
    public <T> T accept(Visitor<T> v) {
        return v.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Symbol symbol = (Symbol) o;
        return name.equals(symbol.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return name;
    }
}

/**
 * Addition: (a + b)
 */
class Plus extends Exp {
    public final Exp a;
    public final Exp b;

    public Plus(Exp a, Exp b) {
        this.a = Objects.requireNonNull(a);
        this.b = Objects.requireNonNull(b);
    }

    @Override
    public <T> T accept(Visitor<T> v) {
        return v.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Plus plus = (Plus) o;
        return a.equals(plus.a) && b.equals(plus.b);
    }

    @Override
    public int hashCode() {
        return Objects.hash("+", a, b);
    }

    @Override
    public String toString() {
        return "(" + a + " + " + b + ")";
    }
}

/**
 * Subtraction: (a - b)
 */
class Minus extends Exp {
    public final Exp a;
    public final Exp b;

    public Minus(Exp a, Exp b) {
        this.a = Objects.requireNonNull(a);
        this.b = Objects.requireNonNull(b);
    }

    @Override
    public <T> T accept(Visitor<T> v) {
        return v.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Minus minus = (Minus) o;
        return a.equals(minus.a) && b.equals(minus.b);
    }

    @Override
    public int hashCode() {
        return Objects.hash("-", a, b);
    }

    @Override
    public String toString() {
        return "(" + a + " - " + b + ")";
    }
}

/**
 * Multiplication: (a * b)
 */
class Multiply extends Exp {
    public final Exp a;
    public final Exp b;

    public Multiply(Exp a, Exp b) {
        this.a = Objects.requireNonNull(a);
        this.b = Objects.requireNonNull(b);
    }

    @Override
    public <T> T accept(Visitor<T> v) {
        return v.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Multiply multiply = (Multiply) o;
        return a.equals(multiply.a) && b.equals(multiply.b);
    }

    @Override
    public int hashCode() {
        return Objects.hash("*", a, b);
    }

    @Override
    public String toString() {
        return "(" + a + " * " + b + ")";
    }
}

/**
 * Division: (a / b)
 */
class Divide extends Exp {
    public final Exp a;
    public final Exp b;

    public Divide(Exp a, Exp b) {
        this.a = Objects.requireNonNull(a);
        this.b = Objects.requireNonNull(b);
    }

    @Override
    public <T> T accept(Visitor<T> v) {
        return v.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Divide divide = (Divide) o;
        return a.equals(divide.a) && b.equals(divide.b);
    }

    @Override
    public int hashCode() {
        return Objects.hash("/", a, b);
    }

    @Override
    public String toString() {
        return "(" + a + " / " + b + ")";
    }
}

/**
 * Power / Exponentiation: (base ^ exp)
 */
class Power extends Exp {
    public final Exp base;
    public final Exp exp;

    public Power(Exp base, Exp exp) {
        this.base = Objects.requireNonNull(base);
        this.exp = Objects.requireNonNull(exp);
    }

    @Override
    public <T> T accept(Visitor<T> v) {
        return v.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Power power = (Power) o;
        return base.equals(power.base) && exp.equals(power.exp);
    }

    @Override
    public int hashCode() {
        return Objects.hash("^", base, exp);
    }

    @Override
    public String toString() {
        return "(" + base + " ^ " + exp + ")";
    }
}

/**
 * Unary Negation: (-a)
 */
class UnaryMinus extends Exp {
    public final Exp a;

    public UnaryMinus(Exp a) {
        this.a = Objects.requireNonNull(a);
    }

    @Override
    public <T> T accept(Visitor<T> v) {
        return v.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UnaryMinus that = (UnaryMinus) o;
        return a.equals(that.a);
    }

    @Override
    public int hashCode() {
        return Objects.hash("neg", a);
    }

    @Override
    public String toString() {
        return "(-" + a + ")";
    }
}

/**
 * Standard Function Call: f(arg1, arg2, ...) e.g. sin(x), cos(x), ln(x), exp(x)
 */
class FunctionCall extends Exp {
    public final String name;
    public final List<Exp> args;

    public FunctionCall(String name, List<Exp> args) {
        this.name = Objects.requireNonNull(name);
        this.args = Collections.unmodifiableList(new ArrayList<>(args));
    }

    public FunctionCall(String name, Exp singleArg) {
        this(name, Collections.singletonList(singleArg));
    }

    @Override
    public <T> T accept(Visitor<T> v) {
        return v.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FunctionCall that = (FunctionCall) o;
        return name.equals(that.name) && args.equals(that.args);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, args);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name).append("(");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(args.get(i).toString());
        }
        sb.append(")");
        return sb.toString();
    }
}
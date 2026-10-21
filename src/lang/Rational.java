package lang;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Exact arbitrary-precision rational number (p/q) for symbolic mathematics.
 * Guaranteed canonical form: gcd(p, q) = 1 and q > 0.
 */
public class Rational implements Comparable<Rational> {
    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
    public static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);
    public static final Rational MINUS_ONE = new Rational(BigInteger.valueOf(-1), BigInteger.ONE);

    private final BigInteger num;
    private final BigInteger den;

    public Rational(long n) {
        this(BigInteger.valueOf(n), BigInteger.ONE);
    }

    public Rational(BigInteger num, BigInteger den) {
        if (den.equals(BigInteger.ZERO)) {
            throw new ArithmeticException("Division by zero in Rational construction");
        }
        if (num.equals(BigInteger.ZERO)) {
            this.num = BigInteger.ZERO;
            this.den = BigInteger.ONE;
            return;
        }

        // Ensure denominator is positive
        if (den.signum() < 0) {
            num = num.negate();
            den = den.negate();
        }

        BigInteger g = num.gcd(den);
        this.num = num.divide(g);
        this.den = den.divide(g);
    }

    public static Rational of(String str) {
        str = str.trim();
        if (str.contains("/")) {
            String[] parts = str.split("/");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid rational format: " + str);
            }
            return new Rational(new BigInteger(parts[0].trim()), new BigInteger(parts[1].trim()));
        } else {
            return new Rational(new BigInteger(str), BigInteger.ONE);
        }
    }

    public BigInteger getNum() {
        return num;
    }

    public BigInteger getDen() {
        return den;
    }

    public boolean isZero() {
        return num.equals(BigInteger.ZERO);
    }

    public boolean isOne() {
        return num.equals(BigInteger.ONE) && den.equals(BigInteger.ONE);
    }

    public boolean isMinusOne() {
        return num.equals(BigInteger.valueOf(-1)) && den.equals(BigInteger.ONE);
    }

    public boolean isInteger() {
        return den.equals(BigInteger.ONE);
    }

    public boolean isNegative() {
        return num.signum() < 0;
    }

    public Rational add(Rational other) {
        BigInteger newNum = this.num.multiply(other.den).add(other.num.multiply(this.den));
        BigInteger newDen = this.den.multiply(other.den);
        return new Rational(newNum, newDen);
    }

    public Rational subtract(Rational other) {
        BigInteger newNum = this.num.multiply(other.den).subtract(other.num.multiply(this.den));
        BigInteger newDen = this.den.multiply(other.den);
        return new Rational(newNum, newDen);
    }

    public Rational multiply(Rational other) {
        return new Rational(this.num.multiply(other.num), this.den.multiply(other.den));
    }

    public Rational divide(Rational other) {
        if (other.isZero()) {
            throw new ArithmeticException("Division by zero in Rational arithmetic");
        }
        return new Rational(this.num.multiply(other.den), this.den.multiply(other.num));
    }

    public Rational negate() {
        return new Rational(this.num.negate(), this.den);
    }

    public Rational pow(int exponent) {
        if (exponent == Integer.MIN_VALUE) {
            throw new ArithmeticException("Integer exponent is outside the supported range");
        }
        if (exponent == 0) {
            return ONE;
        }
        if (isZero()) {
            if (exponent < 0) throw new ArithmeticException("0 cannot be raised to negative power");
            return ZERO;
        }
        if (exponent > 0) {
            return new Rational(this.num.pow(exponent), this.den.pow(exponent));
        } else {
            return new Rational(this.den.pow(-exponent), this.num.pow(-exponent));
        }
    }

    /**
     * Computes an integer power only when the exponent fits safely in a Java int.
     * The minimum int is excluded because negating it overflows.
     */
    public Rational pow(BigInteger exponent) {
        final int intExponent;
        try {
            intExponent = exponent.intValueExact();
        } catch (ArithmeticException e) {
            throw new ArithmeticException("Integer exponent is outside the supported range: " + exponent);
        }
        return pow(intExponent);
    }

    @Override
    public int compareTo(Rational other) {
        return this.num.multiply(other.den).compareTo(other.num.multiply(this.den));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Rational rational = (Rational) o;
        return num.equals(rational.num) && den.equals(rational.den);
    }

    @Override
    public int hashCode() {
        return Objects.hash(num, den);
    }

    @Override
    public String toString() {
        if (den.equals(BigInteger.ONE)) {
            return num.toString();
        }
        return num.toString() + "/" + den.toString();
    }
}

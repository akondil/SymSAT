package lang;

import java.util.*;

/**
 * Exact, budgeted sparse polynomial normalization over QQ. This API is deliberately
 * separate from rewrite-theoretic prove: user equations never influence its result.
 * Results include canonical residual polynomials, independently checkable by a CAS.
 */
public final class PolynomialVerifier {
    public enum Status { EQUAL, NOT_EQUAL, UNSUPPORTED, RESOURCE_LIMIT }
    public static final class Result {
        public final Status status;
        public final String residual;
        public final String reason;
        public final int terms;
        public final long products;
        private Result(Status status, String residual, String reason, int terms, long products) {
            this.status = status; this.residual = residual; this.reason = reason;
            this.terms = terms; this.products = products;
        }
    }
    public static Result check(String left, String right) {
        return check(left, right, 10000, 1000000);
    }
    public static Result check(String left, String right, int maxTerms, long maxProducts) {
        if (maxTerms < 1 || maxProducts < 1) throw new IllegalArgumentException("Positive budgets required");
        Normalizer normalizer = new Normalizer(maxTerms, maxProducts);
        try {
            Poly difference = normalizer.subtract(Interpreter.parseString(left).accept(normalizer),
                Interpreter.parseString(right).accept(normalizer));
            String residual = difference.render();
            return new Result(difference.coefficients.isEmpty() ? Status.EQUAL : Status.NOT_EQUAL,
                residual, "", difference.coefficients.size(), normalizer.products);
        } catch (Budget e) {
            return new Result(Status.RESOURCE_LIMIT, "", e.getMessage(), -1, normalizer.products);
        } catch (UnsupportedOperationException | ParseException | ArithmeticException | TokenMgrError e) {
            return new Result(Status.UNSUPPORTED, "", e.getMessage(), -1, normalizer.products);
        }
    }
    private static final class Budget extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Budget(String reason) { super(reason); }
    }
    private static final class Monomial implements Comparable<Monomial> {
        final SortedMap<String, Integer> powers;
        Monomial(Map<String, Integer> powers) { this.powers = Collections.unmodifiableSortedMap(new TreeMap<>(powers)); }
        Monomial multiply(Monomial other) {
            Map<String, Integer> result = new TreeMap<>(powers);
            for (Map.Entry<String, Integer> e : other.powers.entrySet()) {
                long power = (long) result.getOrDefault(e.getKey(), 0) + e.getValue();
                if (power > Integer.MAX_VALUE) throw new Budget("Monomial degree overflow");
                result.put(e.getKey(), (int) power);
            }
            return new Monomial(result);
        }
        public int compareTo(Monomial other) {
            Iterator<Map.Entry<String, Integer>> a = powers.entrySet().iterator(), b = other.powers.entrySet().iterator();
            while (a.hasNext() && b.hasNext()) {
                Map.Entry<String, Integer> x = a.next(), y = b.next();
                int order = x.getKey().compareTo(y.getKey());
                if (order == 0) order = Integer.compare(x.getValue(), y.getValue());
                if (order != 0) return order;
            }
            return Boolean.compare(a.hasNext(), b.hasNext());
        }
        String render() {
            List<String> factors = new ArrayList<>();
            for (Map.Entry<String, Integer> e : powers.entrySet()) {
                factors.add(e.getKey() + (e.getValue() == 1 ? "" : "^" + e.getValue()));
            }
            return String.join("*", factors);
        }
    }
    private static final Monomial UNIT = new Monomial(Collections.emptyMap());
    private static final class Poly {
        final SortedMap<Monomial, Rational> coefficients = new TreeMap<>();
        String render() {
            List<String> terms = new ArrayList<>();
            for (Map.Entry<Monomial, Rational> e : coefficients.entrySet()) {
                String monomial = e.getKey().render();
                terms.add("(" + e.getValue() + ")" + (monomial.isEmpty() ? "" : "*" + monomial));
            }
            return terms.isEmpty() ? "0" : String.join(" + ", terms);
        }
        Rational constant() {
            if (coefficients.isEmpty()) return Rational.ZERO;
            if (coefficients.size() == 1 && coefficients.containsKey(UNIT)) return coefficients.get(UNIT);
            throw new UnsupportedOperationException("A constant polynomial denominator/base is required");
        }
    }
    private static final class Normalizer implements Visitor<Poly> {
        final int maxTerms;
        final long maxProducts;
        long products;
        Normalizer(int maxTerms, long maxProducts) { this.maxTerms = maxTerms; this.maxProducts = maxProducts; }
        void insert(Poly p, Monomial m, Rational coefficient) {
            Rational sum = p.coefficients.getOrDefault(m, Rational.ZERO).add(coefficient);
            if (sum.isZero()) p.coefficients.remove(m);
            else {
                if (!p.coefficients.containsKey(m) && p.coefficients.size() >= maxTerms) throw new Budget("Term budget reached");
                p.coefficients.put(m, sum);
            }
        }
        Poly constant(Rational value) { Poly p = new Poly(); insert(p, UNIT, value); return p; }
        Poly add(Poly a, Poly b) {
            Poly p = new Poly();
            for (Map.Entry<Monomial, Rational> e : a.coefficients.entrySet()) insert(p, e.getKey(), e.getValue());
            for (Map.Entry<Monomial, Rational> e : b.coefficients.entrySet()) insert(p, e.getKey(), e.getValue());
            return p;
        }
        Poly scale(Poly a, Rational c) {
            Poly p = new Poly();
            for (Map.Entry<Monomial, Rational> e : a.coefficients.entrySet()) insert(p, e.getKey(), e.getValue().multiply(c));
            return p;
        }
        Poly subtract(Poly a, Poly b) { return add(a, scale(b, Rational.MINUS_ONE)); }
        Poly multiply(Poly a, Poly b) {
            Poly p = new Poly();
            for (Map.Entry<Monomial, Rational> x : a.coefficients.entrySet()) {
                for (Map.Entry<Monomial, Rational> y : b.coefficients.entrySet()) {
                    if (products >= maxProducts) throw new Budget("Coefficient-product budget reached");
                    products++;
                    insert(p, x.getKey().multiply(y.getKey()), x.getValue().multiply(y.getValue()));
                }
            }
            return p;
        }
        public Poly visit(Const e) { return constant(e.val); }
        public Poly visit(Symbol e) {
            if (e.isPatternVariable()) throw new UnsupportedOperationException("Pattern variables are not polynomial indeterminates");
            Poly p = new Poly(); insert(p, new Monomial(Collections.singletonMap(e.name, 1)), Rational.ONE); return p;
        }
        public Poly visit(Plus e) { return add(e.a.accept(this), e.b.accept(this)); }
        public Poly visit(Minus e) { return subtract(e.a.accept(this), e.b.accept(this)); }
        public Poly visit(Multiply e) { return multiply(e.a.accept(this), e.b.accept(this)); }
        public Poly visit(UnaryMinus e) { return scale(e.a.accept(this), Rational.MINUS_ONE); }
        public Poly visit(Divide e) { return scale(e.a.accept(this), Rational.ONE.divide(e.b.accept(this).constant())); }
        public Poly visit(Power e) {
            Poly base = e.base.accept(this);
            Rational exponent = e.exp.accept(this).constant();
            if (!exponent.isInteger()) throw new UnsupportedOperationException("Integer polynomial exponents required");
            int n = exponent.getNum().intValueExact();
            if (n < 0) return constant(base.constant().pow(n));
            Poly result = constant(Rational.ONE);
            while (n > 0) {
                if ((n & 1) != 0) result = multiply(result, base);
                n >>>= 1;
                if (n > 0) base = multiply(base, base);
            }
            return result;
        }
        public Poly visit(FunctionCall e) { throw new UnsupportedOperationException("Function calls are outside QQ polynomials: " + e.name); }
    }
    private PolynomialVerifier() {}
}

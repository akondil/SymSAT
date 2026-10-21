package lang;

import java.math.BigInteger;
import java.util.*;

/** Per-query deterministic limits, not a wall-clock or process memory guarantee. */
public final class SearchLimits {
    public final int maxIterations, nodeLimit, maxIntegerBits, maxDepth, maxInputNodes;
    public final long maxWork, maxBindings;
    public SearchLimits(int maxIterations, int nodeLimit) {
        this(maxIterations, nodeLimit, 10_000_000L, 1_000_000L, 65536, 256, 100000);
    }
    public SearchLimits(int maxIterations, int nodeLimit, long maxWork, long maxBindings,
                        int maxIntegerBits, int maxDepth, int maxInputNodes) {
        if (maxIterations < 1 || nodeLimit < 1 || maxWork < 1 || maxBindings < 1
                || maxIntegerBits < 1 || maxDepth < 1 || maxInputNodes < 1)
            throw new IllegalArgumentException("All search limits must be positive");
        this.maxIterations=maxIterations; this.nodeLimit=nodeLimit; this.maxWork=maxWork;
        this.maxBindings=maxBindings; this.maxIntegerBits=maxIntegerBits;
        this.maxDepth=maxDepth; this.maxInputNodes=maxInputNodes;
    }
    public static SearchLimits defaults() { return new SearchLimits(10, 5000); }

    /** Iterative validation happens before recursive graph insertion or Context substitution. */
    public void validateInput(Exp expression) {
        Deque<Exp> terms = new ArrayDeque<>(); Deque<Integer> depths = new ArrayDeque<>();
        terms.push(Objects.requireNonNull(expression)); depths.push(1); int count=0;
        while (!terms.isEmpty()) {
            Exp term=terms.pop(); int depth=depths.pop();
            if (++count > maxInputNodes || depth > maxDepth)
                throw new SearchLimitException(EGraph.StopReason.INPUT_LIMIT, "Input size or depth limit reached");
            if (term instanceof Const) checkRational(((Const)term).val);
            List<Exp> children=children(term);
            if ((long)count+terms.size()+children.size()>maxInputNodes)
                throw new SearchLimitException(EGraph.StopReason.INPUT_LIMIT, "Input node limit reached");
            for (Exp child : children) { terms.push(child); depths.push(depth+1); }
        }
    }
    static List<Exp> children(Exp e) {
        if(e instanceof Plus)return List.of(((Plus)e).a,((Plus)e).b);
        if(e instanceof Minus)return List.of(((Minus)e).a,((Minus)e).b);
        if(e instanceof Multiply)return List.of(((Multiply)e).a,((Multiply)e).b);
        if(e instanceof Divide)return List.of(((Divide)e).a,((Divide)e).b);
        if(e instanceof Power)return List.of(((Power)e).base,((Power)e).exp);
        if(e instanceof UnaryMinus)return List.of(((UnaryMinus)e).a);
        if(e instanceof FunctionCall)return ((FunctionCall)e).args;
        return Collections.emptyList();
    }
    void checkRational(Rational r) { checkBits(r.getNum().abs().bitLength(), r.getDen().bitLength()); }
    private void checkBits(long numerator, long denominator) {
        if (numerator > maxIntegerBits || denominator > maxIntegerBits)
            throw new SearchLimitException(EGraph.StopReason.INTEGER_LIMIT, "Integer bit limit reached");
    }
    private static long productBits(BigInteger a, BigInteger b) {
        if(a.signum()==0 || b.signum()==0)return 0;
        if(a.abs().equals(BigInteger.ONE))return b.abs().bitLength();
        if(b.abs().equals(BigInteger.ONE))return a.abs().bitLength();
        return (long)a.abs().bitLength()+b.abs().bitLength();
    }
    /** Conservative pre-allocation bounds; may decline a fold that would reduce after gcd. */
    void checkFold(String op, List<Rational> a) {
        for (Rational r : a) checkRational(r);
        if(a.size()!=2)return;
        Rational x=a.get(0), y=a.get(1);
        switch(op) {
            case "+": case "-":
                checkBits(Math.max(productBits(x.getNum(),y.getDen()), productBits(y.getNum(),x.getDen()))+1,
                        productBits(x.getDen(),y.getDen())); break;
            case "*":
                checkBits(productBits(x.getNum(),y.getNum()), productBits(x.getDen(),y.getDen())); break;
            case "/":
                checkBits(productBits(x.getNum(),y.getDen()), productBits(x.getDen(),y.getNum())); break;
            case "^":
                if (y.isInteger()) {
                    BigInteger exponent=y.getNum().abs();
                    for(BigInteger value:List.of(x.getNum().abs(),x.getDen()))
                        if(value.compareTo(BigInteger.ONE)>0 && exponent.compareTo(
                                BigInteger.valueOf(maxIntegerBits / value.bitLength()))>0)
                            throw new SearchLimitException(EGraph.StopReason.INTEGER_LIMIT, "Power would exceed integer bit limit");
                }
                break;
            default: break;
        }
    }
}

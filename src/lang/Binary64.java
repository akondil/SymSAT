package lang;

import java.math.BigInteger;

/** Exact rational-to-binary64 rounding using integer quotient/remainder, ties to even. */
final class Binary64 {
    static double fromRational(Rational rational) {
        BigInteger n=rational.getNum().abs(), d=rational.getDen();
        if(n.signum()==0)return 0.0;
        int exponent=n.bitLength()-d.bitLength();
        int comparison=exponent>=0?n.compareTo(d.shiftLeft(exponent)):n.shiftLeft(-exponent).compareTo(d);
        if(comparison<0)exponent--;
        if(exponent>1023)throw new IllegalArgumentException("Constant overflows binary64");
        int shift=exponent < -1022 ? 1074 : 52-exponent;
        BigInteger numerator=shift>=0?n.shiftLeft(shift):n;
        BigInteger denominator=shift>=0?d:d.shiftLeft(-shift);
        BigInteger[] division=numerator.divideAndRemainder(denominator);
        BigInteger significand=division[0];
        int half=division[1].shiftLeft(1).compareTo(denominator);
        if(half>0 || (half==0 && significand.testBit(0)))significand=significand.add(BigInteger.ONE);
        double rounded=Math.scalb(significand.doubleValue(),exponent < -1022 ? -1074 : exponent-52);
        if(!Double.isFinite(rounded))throw new IllegalArgumentException("Constant overflows binary64");
        if(rounded==0.0)throw new IllegalArgumentException("Nonzero constant rounds to zero in binary64");
        return rational.isNegative() ? -rounded : rounded;
    }
    private Binary64() {}
}

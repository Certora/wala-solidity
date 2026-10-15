/*
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://eclipse.org.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: {name license(s), version(s), and
 * exceptions or additional permissions here}.
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package com.certora.wala.analysis.rounding.eval;

import java.math.BigInteger;

/**
 * An exact rational, the value domain of the real-valued interpretation of Q in the
 * differential harness. Always normalized: positive denominator, reduced fraction.
 */
public final class Rational implements Comparable<Rational> {
	public final BigInteger num;
	public final BigInteger den;

	public static final Rational ZERO = of(BigInteger.ZERO);
	public static final Rational ONE = of(BigInteger.ONE);

	public static Rational of(BigInteger n) {
		return new Rational(n, BigInteger.ONE);
	}

	public static Rational of(long n) {
		return of(BigInteger.valueOf(n));
	}

	Rational(BigInteger n, BigInteger d) {
		if (d.signum() == 0) {
			throw new ArithmeticException("zero denominator");
		}
		if (d.signum() < 0) {
			n = n.negate();
			d = d.negate();
		}
		BigInteger g = n.gcd(d);
		if (!g.equals(BigInteger.ONE)) {
			n = n.divide(g);
			d = d.divide(g);
		}
		this.num = n;
		this.den = d;
	}

	public Rational plus(Rational o) {
		return new Rational(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
	}

	public Rational minus(Rational o) {
		return new Rational(num.multiply(o.den).subtract(o.num.multiply(den)), den.multiply(o.den));
	}

	public Rational times(Rational o) {
		return new Rational(num.multiply(o.num), den.multiply(o.den));
	}

	public Rational dividedBy(Rational o) {
		return new Rational(num.multiply(o.den), den.multiply(o.num));
	}

	public Rational negate() {
		return new Rational(num.negate(), den);
	}

	public boolean isInteger() {
		return den.equals(BigInteger.ONE);
	}

	@Override
	public int compareTo(Rational o) {
		return num.multiply(o.den).compareTo(o.num.multiply(den));
	}

	/** Compare with an integer without constructing a Rational. */
	public int compareTo(BigInteger i) {
		return num.compareTo(i.multiply(den));
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Rational r && num.equals(r.num) && den.equals(r.den);
	}

	@Override
	public int hashCode() {
		return num.hashCode() * 31 + den.hashCode();
	}

	@Override
	public String toString() {
		return isInteger() ? num.toString() : num + "/" + den;
	}
}

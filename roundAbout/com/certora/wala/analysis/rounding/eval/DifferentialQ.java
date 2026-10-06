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

import java.io.File;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.certora.wala.analysis.rounding.RoundingAnalysis;
import com.certora.wala.cast.solidity.client.SolidityRoundingAnalysisEngineJSON;
import com.ibm.wala.cast.loader.AstMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.PropagationCallGraphBuilder;
import com.ibm.wala.util.CancelException;

/**
 * The differential harness: interprets Q over the integers and over the rationals on shared
 * random inputs and reports, per entrypoint method, how the two runs relate - the concrete
 * samples of the relation each Phase 2 verdict abstracts. One CSV row per method:
 *
 * <pre>
 * method | params | samples | above | below | equal | ambiguous | discarded | reasons
 * </pre>
 *
 * above/below/equal compare the integer run to the rational run; ambiguous counts samples
 * whose category depends on the GuardedMerge strictness Q does not record. Verdict checking
 * is an offline join of this CSV with the baseline extracts.
 */
public class DifferentialQ {

	static class CapturingEngine extends SolidityRoundingAnalysisEngineJSON {
		CallGraph cg;
		PointerAnalysis<InstanceKey> pa;

		CapturingEngine(File conf, String json) throws java.io.IOException {
			super(conf, json);
		}

		@Override
		public org.json.JSONObject performAnalysis(PropagationCallGraphBuilder builder) throws CancelException {
			this.cg = builder.getCallGraph();
			this.pa = builder.getPointerAnalysis();
			return super.performAnalysis(builder);
		}
	}

	public static void main(String... args) throws Exception {
		String conf = args[0];
		String out = args[1];
		assert "--combined".equalsIgnoreCase(args[2]);
		String asts = args[3];
		int samples = args.length > 4 ? Integer.parseInt(args[4]) : 10_000;

		CapturingEngine e = new CapturingEngine(new File(conf), asts);
		e.analyze();

		RoundingAnalysis ra = new RoundingAnalysis(e.cg, e.pa);
		QEval qe = new QEval(e.cg, n -> {
			try {
				return n.getIR() == null ? null : ra.getGraph(n);
			} catch (RuntimeException ex) {
				return null;
			}
		});

		try (PrintWriter w = new PrintWriter(out)) {
			w.println("method\tparams\tsamples\tabove\tbelow\tequal\tambiguous\tdiscarded\treasons");
			for (CGNode n : e.cg.getEntrypointNodes()) {
				if (!(n.getMethod() instanceof AstMethod) || n.getIR() == null) {
					continue;
				}
				w.println(evaluate(qe, n, samples));
			}
		}
		System.out.println("wrote " + out);
	}

	static String evaluate(QEval qe, CGNode n, int samples) {
		int params = n.getMethod().getNumberOfParameters();
		Random rng = new Random(n.getMethod().toString().hashCode());
		int above = 0, below = 0, equal = 0, ambiguous = 0, discarded = 0;
		Map<String, Integer> reasons = new HashMap<>();

		for (int i = 0; i < samples; i++) {
			List<BigInteger> draw = new ArrayList<>(params);
			for (int p = 0; p < params; p++) {
				draw.add(stratified(rng));
			}
			long leafSeed = rng.nextLong();
			try {
				int c1 = sample(qe, n, draw, leafSeed, QEval.Strictness.THEN_ON_EQUAL);
				if (qe.sawBoundaryEquality) {
					int c2 = sample(qe, n, draw, leafSeed, QEval.Strictness.ELSE_ON_EQUAL);
					if (c1 != c2) {
						ambiguous++;
						continue;
					}
				}
				if (c1 > 0) {
					above++;
				} else if (c1 < 0) {
					below++;
				} else {
					equal++;
				}
			} catch (QEval.Discard d) {
				discarded++;
				reasons.merge(d.getMessage(), 1, Integer::sum);
			} catch (RuntimeException | StackOverflowError ex) {
				discarded++;
				reasons.merge("error:" + ex.getClass().getSimpleName(), 1, Integer::sum);
			}
		}
		return n.getMethod().getDeclaringClass().getName() + "." + n.getMethod().toString().replace('\t', ' ') + "\t"
				+ params + "\t" + samples + "\t" + above + "\t" + below + "\t" + equal + "\t" + ambiguous + "\t"
				+ discarded + "\t" + reasons;
	}

	/** One sample, both domains, shared leaves; returns sign(int - rational). */
	private static int sample(QEval qe, CGNode n, List<BigInteger> draw, long leafSeed, QEval.Strictness s) {
		Random leafRng = new Random(leafSeed);
		QEval.LeafOracle leaves = new QEval.LeafOracle(() -> stratified(leafRng));

		List<BigInteger> intArgs = draw;
		BigInteger vi = qe.run(n, intArgs, new QEval.IntDom(), leaves, s);
		boolean eq1 = qe.sawBoundaryEquality;

		List<Rational> ratArgs = new ArrayList<>(draw.size());
		for (BigInteger b : draw) {
			ratArgs.add(Rational.of(b));
		}
		Rational vr = qe.run(n, ratArgs, new QEval.RatDom(), leaves, s);
		qe.sawBoundaryEquality |= eq1;

		return Rational.of(vi).compareTo(vr);
	}

	/**
	 * Stratified non-negative draws: the small values where off-by-one lives, protocol-scale
	 * fixed-point magnitudes, and uniform bits - all far below 2^255 so that no sampled
	 * computation can overflow, matching the analysis's global no-overflow assumption.
	 */
	static BigInteger stratified(Random rng) {
		return switch (rng.nextInt(8)) {
		case 0 -> BigInteger.valueOf(rng.nextInt(4));                 // 0..3
		case 1 -> BigInteger.valueOf(rng.nextInt(1000));              // small
		case 2 -> BigInteger.TEN.pow(18).add(BigInteger.valueOf(rng.nextInt(7) - 3)); // ~1e18
		case 3 -> BigInteger.TEN.pow(27).add(BigInteger.valueOf(rng.nextInt(7) - 3)); // ~1e27
		case 4 -> BigInteger.valueOf(Math.abs(rng.nextLong()) % 1_000_000_000L)
				.multiply(BigInteger.TEN.pow(18));                    // exact multiples of 1e18
		case 5 -> new BigInteger(64, rng);
		case 6 -> new BigInteger(96, rng);
		default -> new BigInteger(112, rng);
		};
	}
}

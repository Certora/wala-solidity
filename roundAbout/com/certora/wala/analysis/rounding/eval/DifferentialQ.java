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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

import com.certora.wala.analysis.rounding.Direction;
import com.certora.wala.analysis.rounding.RoundingAnalysis;
import com.certora.wala.analysis.rounding.RoundingGraph;
import com.certora.wala.cast.solidity.client.SolidityRoundingAnalysisEngineJSON;
import com.certora.wala.cast.solidity.util.JSONOutput;
import com.ibm.wala.cast.loader.AstMethod;
import com.ibm.wala.cast.loader.AstMethod.DebuggingInformation;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.PropagationCallGraphBuilder;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.util.CancelException;

/**
 * The differential harness: interprets Q over the integers and over the rationals on shared
 * inputs and reports, per call-graph node, how the two runs relate - the concrete samples of
 * the relation each Phase 2 verdict abstracts. One TSV row per node:
 *
 * <pre>
 * method | node | entry | position | verdict | params | samples | above | below | equal |
 *   ambiguous | discarded | reasons | searched | searchAbove | searchBelow | searchEqual |
 *   witnessAbove | witnessBelow
 * </pre>
 *
 * above/below/equal compare the integer run to the rational run over {@code samples}
 * stratified random inputs; ambiguous counts samples whose category depends on the
 * GuardedMerge strictness Q does not record. {@code verdict} is the analysis's return
 * direction for exactly this node with all-exact inputs ({@link RoundingAnalysis#analyzeForNode},
 * the call the JSON report makes for entry points), so a row needs no join by name.
 *
 * <p>Usage: {@code DifferentialQ <conf> <out.tsv> --combined <asts> [samples] [filter]
 * [--all-nodes] [--search=N]}. By default only entry points are sampled. {@code --all-nodes}
 * adds every other node whose all-exact verdict is worth testing: one returning a single
 * value, and either non-Exact or containing a division or call. {@code --search=N} then
 * spends up to N further inputs per row looking for the side the stratified samples did not
 * show: for Indet, the missing side of a two-sided witness; for Up or Down, a sample that
 * refutes it; for Exact, a non-equal sample, if the node itself divides. Every witness is printed with its inputs so it can be replayed.
 * The stratified phase is unchanged by either flag.
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
		int samples = 10_000;
		boolean samplesGiven = false;
		Pattern filter = null;
		boolean allNodes = false;
		int search = 0;
		for (int i = 4; i < args.length; i++) {
			String a = args[i];
			if (a.equals("--all-nodes")) {
				allNodes = true;
			} else if (a.startsWith("--search=")) {
				search = Integer.parseInt(a.substring("--search=".length()));
			} else if (!samplesGiven && a.matches("\\d+")) {
				samples = Integer.parseInt(a);
				samplesGiven = true;
			} else if (filter == null) {
				filter = Pattern.compile(a);
			} else {
				throw new IllegalArgumentException("unexpected argument " + a);
			}
		}

		CapturingEngine e = new CapturingEngine(new File(conf), asts);
		e.analyze();

		RoundingAnalysis ra = new RoundingAnalysis(e.cg, e.pa);
		QEval qe = new QEval(e.cg, n -> graphOrNull(ra, n));

		Set<CGNode> entries = new HashSet<>(e.cg.getEntrypointNodes());
		Iterable<CGNode> nodes = allNodes ? e.cg : e.cg.getEntrypointNodes();
		try (PrintWriter w = new PrintWriter(out)) {
			w.println("method\tnode\tentry\tposition\tverdict\tparams\tsamples\tabove\tbelow\tequal\tambiguous"
					+ "\tdiscarded\treasons\tsearched\tsearchAbove\tsearchBelow\tsearchEqual\twitnessAbove\twitnessBelow");
			for (CGNode n : nodes) {
				if (!(n.getMethod() instanceof AstMethod) || n.getIR() == null) {
					continue;
				}
				if (filter != null && !filter.matcher(n.getMethod().toString()).find()) {
					continue;
				}
				boolean entry = entries.contains(n);
				String verdict = verdictOf(ra, e.cg, n);
				RoundingGraph q = graphOrNull(ra, n);
				boolean divides = q != null && q.equationOrder().stream().anyMatch(x -> x instanceof RoundingGraph.Div);
				boolean calls = q != null && q.equationOrder().stream().anyMatch(x -> x instanceof RoundingGraph.Call);
				if (!entry && (q == null || q.returnVns().length != 1 || verdict.startsWith("tuple")
						|| verdict.startsWith("error") || (verdict.equals("Neither") && !divides && !calls))) {
					continue;
				}
				w.println(evaluate(qe, n, entry, verdict, divides, samples, search));
			}
		}
		System.out.println("wrote " + out);
	}

	private static RoundingGraph graphOrNull(RoundingAnalysis ra, CGNode n) {
		try {
			return n.getIR() == null ? null : ra.getGraph(n);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/** The analysis's return direction for {@code n} with all-exact inputs; tuples per component. */
	static String verdictOf(RoundingAnalysis ra, CallGraph cg, CGNode n) {
		try {
			Map<FieldReference, Direction> r = ra.analyzeForNode(cg, n).getReturnRounding();
			if (r.size() == 1 && r.containsKey(null)) {
				return String.valueOf(r.get(null));
			}
			StringJoiner j = new StringJoiner(",", "tuple(", ")");
			r.entrySet().stream().sorted(Comparator.comparing(x -> String.valueOf(x.getKey())))
					.forEach(x -> j.add(x.getKey().getName() + "=" + x.getValue()));
			return j.toString();
		} catch (CancelException | RuntimeException ex) {
			return "error:" + ex.getClass().getSimpleName();
		}
	}

	/** The method's source position, formatted as the JSON report's {@code methodPosition}. */
	static String position(CGNode n) {
		DebuggingInformation dbg = ((AstMethod) n.getMethod()).debugInfo();
		return dbg.getCodeBodyPosition().getURL().getPath() + ":" + JSONOutput.toLocalPos(dbg.getCodeBodyPosition());
	}

	/** One input: the parameters, the seed of the leaf draws, and whether leaves draw small values. */
	record Input(List<BigInteger> args, long leafSeed, boolean smallLeaves) {
		@Override
		public String toString() {
			return "args=" + args + ";leafSeed=" + leafSeed + (smallLeaves ? ";smallLeaves" : "");
		}
	}

	/** classify's result when the two strictness passes disagree. */
	private static final int AMBIGUOUS = 2;

	/** sign(int - rational) on one input, or AMBIGUOUS; throws when the input cannot be evaluated. */
	private static int classify(QEval qe, CGNode n, Input in) {
		int c1 = sample(qe, n, in, QEval.Strictness.THEN_ON_EQUAL);
		if (qe.sawBoundaryEquality) {
			int c2 = sample(qe, n, in, QEval.Strictness.ELSE_ON_EQUAL);
			if (c1 != c2) {
				return AMBIGUOUS;
			}
		}
		return c1;
	}

	static String evaluate(QEval qe, CGNode n, boolean entry, String verdict, boolean divides, int samples,
			int search) {
		int params = n.getMethod().getNumberOfParameters();
		Random rng = new Random(n.getMethod().toString().hashCode());
		int above = 0, below = 0, equal = 0, ambiguous = 0, discarded = 0;
		Map<String, Integer> reasons = new HashMap<>();
		Input witAbove = null, witBelow = null;
		List<Input> pool = new ArrayList<>();

		for (int i = 0; i < samples; i++) {
			List<BigInteger> draw = new ArrayList<>(params);
			for (int p = 0; p < params; p++) {
				draw.add(stratified(rng));
			}
			Input in = new Input(draw, rng.nextLong(), false);
			try {
				int c = classify(qe, n, in);
				if (c == AMBIGUOUS) {
					ambiguous++;
					continue;
				}
				if (pool.size() < POOL) {
					pool.add(in);
				}
				if (c > 0) {
					above++;
					witAbove = witAbove == null ? in : witAbove;
				} else if (c < 0) {
					below++;
					witBelow = witBelow == null ? in : witBelow;
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

		// The witness search: what is still missing after the stratified phase.
		boolean wantAbove, wantBelow;
		switch (verdict) {
		case "Inconsistent" -> {
			wantAbove = above == 0;
			wantBelow = below == 0;
		}
		case "Up" -> {
			wantAbove = false;
			wantBelow = below == 0;
		}
		case "Down" -> {
			wantAbove = above == 0;
			wantBelow = false;
		}
		case "Neither" -> {
			wantAbove = divides && above == 0 && below == 0;
			wantBelow = wantAbove;
		}
		default -> {
			wantAbove = false;
			wantBelow = false;
		}
		}
		boolean refuteExact = verdict.equals("Neither");
		int searched = 0, searchAbove = 0, searchBelow = 0, searchEqual = 0, failedInARow = 0;
		Random srng = new Random(n.getMethod().toString().hashCode() * 31L + 17);
		while (searched < search && (wantAbove || wantBelow)) {
			Input in = candidate(srng, params, pool);
			searched++;
			try {
				int c = classify(qe, n, in);
				failedInARow = 0;
				if (c == AMBIGUOUS) {
					continue;
				}
				if (pool.size() < POOL) {
					pool.add(in);
				} else {
					pool.set(srng.nextInt(POOL), in);
				}
				if (c > 0) {
					searchAbove++;
					witAbove = witAbove == null ? in : witAbove;
					wantAbove = false;
				} else if (c < 0) {
					searchBelow++;
					witBelow = witBelow == null ? in : witBelow;
					wantBelow = false;
				} else {
					searchEqual++;
				}
				if (refuteExact && c != 0) {
					wantAbove = wantBelow = false;
				}
			} catch (RuntimeException | StackOverflowError d) { // Discard included
				if (++failedInARow >= GIVE_UP && pool.isEmpty()) {
					break;
				}
			}
		}

		return n.getMethod().getDeclaringClass().getName() + "." + n.getMethod().toString().replace('\t', ' ') + "\t"
				+ n.getGraphNodeId() + "\t" + (entry ? 1 : 0) + "\t" + position(n) + "\t" + verdict + "\t"
				+ params + "\t" + samples + "\t" + above + "\t" + below + "\t" + equal + "\t" + ambiguous + "\t"
				+ discarded + "\t" + reasons + "\t" + searched + "\t" + searchAbove + "\t" + searchBelow + "\t" + searchEqual + "\t"
				+ (witAbove == null ? "-" : witAbove) + "\t" + (witBelow == null ? "-" : witBelow);
	}

	/** Evaluable inputs kept as mutation seeds. */
	private static final int POOL = 64;

	/** Consecutive unevaluable search inputs, with nothing evaluable yet, after which the search stops. */
	private static final int GIVE_UP = 500;

	/** Small values and values next to powers of ten: where integer and exact division part ways. */
	private static final BigInteger[] SMALL;
	static {
		List<BigInteger> s = new ArrayList<>();
		for (int i = 0; i <= 12; i++) {
			s.add(BigInteger.valueOf(i));
		}
		for (int k : new int[] { 2, 3, 6, 18, 27 }) {
			BigInteger p = BigInteger.TEN.pow(k);
			s.add(p.subtract(BigInteger.ONE));
			s.add(p);
			s.add(p.add(BigInteger.ONE));
		}
		SMALL = s.toArray(BigInteger[]::new);
	}

	private static BigInteger small(Random r) {
		return SMALL[r.nextInt(SMALL.length)];
	}

	/** The next search input: a small-domain draw, or a mutation of an evaluable one. */
	private static Input candidate(Random r, int params, List<Input> pool) {
		int mode = r.nextInt(pool.isEmpty() ? 2 : 5);
		if (mode < 2) {
			List<BigInteger> a = new ArrayList<>(params);
			for (int p = 0; p < params; p++) {
				a.add(small(r));
			}
			return new Input(a, r.nextLong(), mode == 0);
		}
		Input base = pool.get(r.nextInt(pool.size()));
		List<BigInteger> a = new ArrayList<>(base.args());
		long leafSeed = base.leafSeed();
		boolean smallLeaves = base.smallLeaves();
		int changes = 1 + r.nextInt(2);
		for (int k = 0; k < changes; k++) {
			if (params == 0 || r.nextInt(3) == 0) {
				leafSeed = r.nextLong();
				continue;
			}
			int i = r.nextInt(params);
			BigInteger x = a.get(i);
			a.set(i, switch (r.nextInt(9)) {
			case 0 -> x.add(BigInteger.ONE);
			case 1 -> x.signum() > 0 ? x.subtract(BigInteger.ONE) : x;
			case 2 -> x.add(small(r));
			case 3 -> x.shiftLeft(1);
			case 4 -> x.shiftRight(1);
			case 5 -> x.multiply(BigInteger.TEN.pow(1 + r.nextInt(18)));
			case 6 -> small(r);
			case 7 -> a.get(r.nextInt(params));
			default -> stratified(r);
			});
		}
		if (r.nextInt(6) == 0) {
			smallLeaves = !smallLeaves;
		}
		return new Input(a, leafSeed, smallLeaves);
	}

	/** One sample, both domains, shared leaves; returns sign(int - rational). */
	private static int sample(QEval qe, CGNode n, Input in, QEval.Strictness s) {
		Random leafRng = new Random(in.leafSeed());
		QEval.LeafOracle leaves = new QEval.LeafOracle(
				in.smallLeaves() ? () -> small(leafRng) : () -> stratified(leafRng));

		List<BigInteger> draw = in.args();
		BigInteger vi = qe.run(n, draw, new QEval.IntDom(), leaves, s);
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

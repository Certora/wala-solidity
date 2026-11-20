/*
 * Copyright (c) 2024 Certora
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package com.certora.wala.analysis.rounding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.ibm.wala.shrike.shrikeBT.IBinaryOpInstruction;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.util.collections.HashMapFactory;

/**
 * E-graph prototype: lets the egglog rules in {@code roundAbout/egglog} decide the bias, indicator
 * and masked rows of Table 1 (and the flag form), in place of {@link RoundingRecognition}'s
 * matchers for them. The guarded and branch rows depend on control flow and stay in Java.
 *
 * <p>Off unless {@code -DegglogPhase1=<egglog binary>} or the environment variable
 * {@code EGGLOG_PHASE1=<egglog binary>} is set. {@code egglogRules} / {@code EGGLOG_RULES} names
 * the directory holding {@code rules.egg} and {@code phase1.egg} (default
 * {@code roundAbout/egglog}), and {@code egglogPhase1Log} / {@code EGGLOG_PHASE1_LOG} a file that
 * gets one line per method: its signature, the number of candidates, the number the rules read as
 * a ceiling, the milliseconds egglog took, and {@code ok} or the failure.
 *
 * <p>For each method with a candidate, one egglog process runs {@code rules.egg},
 * {@code phase1.egg} and the method's facts (see {@link EgglogExport}), and prints the
 * {@code IntentVal} rows: site, numerator value, divisor value, condition. If egglog cannot be
 * run or fails, the Java matchers decide that method, and the log and standard error say so.
 */
final class EgglogPhase1 {
	static final String EGGLOG = setting("egglogPhase1", "EGGLOG_PHASE1", null);
	private static final String RULES_DIR = setting("egglogRules", "EGGLOG_RULES", "roundAbout/egglog");
	private static final String LOG = setting("egglogPhase1Log", "EGGLOG_PHASE1_LOG", null);
	private static final long TIMEOUT_SECONDS = 20;
	private static final String RUN = "(run-schedule (saturate (run written)) (saturate (run match)))\n"
			+ "(print-function IntentVal :mode csv)\n";

	private static String rules;

	private EgglogPhase1() {
	}

	private static String setting(String property, String variable, String fallback) {
		String v = System.getProperty(property, System.getenv(variable));
		return v == null || v.isEmpty() ? fallback : v;
	}

	static boolean on() {
		return EGGLOG != null;
	}

	/** What the rules read site {@code v} as: {@code ceil(N / D)} where the condition holds. */
	static final class Found {
		/** N: a value number, or -(a call's value) for the product of that quotient call's factors. */
		final int numerator;
		/** The factors behind a negative {@link #numerator}; null otherwise. */
		final int[] productFactors;
		final int divisorVN;
		/** Pairs {phi def, operand index} that must be infeasible; empty if the reading always holds. */
		final List<int[]> dead;

		Found(int numerator, int[] productFactors, int divisorVN, List<int[]> dead) {
			this.numerator = numerator;
			this.productFactors = productFactors;
			this.divisorVN = divisorVN;
			this.dead = dead;
		}
	}

	/**
	 * The rules' readings for the method, by site value number. Null if egglog could not decide
	 * the method; then the Java matchers must. {@code recognition} is only asked for its literals
	 * and its call model, which do not depend on the decisions made here.
	 */
	static Map<Integer, Found> run(IR ir, DefUse du, RoundingRecognition recognition) {
		String method = ir.getMethod().getSignature();
		long start = System.nanoTime();
		try {
			EgglogExport facts = new EgglogExport(ir, du, recognition);
			String text = facts.phase1Facts();
			if (text == null) {
				return Collections.emptyMap(); // no division and no sum or product with an indicator
			}
			List<int[]> rows = egglog(text);
			Map<Integer, Found> found = HashMapFactory.make();
			for (SSABinaryOpInstruction root : facts.rootInstructions()) {
				Found f = choose(root, rows, facts);
				if (f != null) {
					found.put(root.getDef(), f);
				}
			}
			log(method, facts.rootInstructions().size(), found.size(), start, "ok");
			return found;
		} catch (IOException | InterruptedException | RuntimeException | StackOverflowError e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			System.err.println("egglogPhase1: " + method + ": " + e + "; the Java matchers decide this method");
			log(method, -1, -1, start, "FAILED " + e);
			return null;
		}
	}

	/**
	 * Of the rows for {@code root}, the one whose numerator and divisor the root's own expression
	 * reaches first. A value outside that expression is not taken even if it is in the same
	 * e-class: the code may not have computed it when it reaches the root. For a division the
	 * divisor operand itself comes first; an unconditional row comes before a conditional one.
	 */
	private static Found choose(SSABinaryOpInstruction root, List<int[]> rows, EgglogExport facts) {
		int v = root.getDef();
		List<Integer> walk = facts.walk(v);
		Map<Integer, Integer> rank = HashMapFactory.make();
		for (int i = 0; i < walk.size(); i++) {
			rank.put(walk.get(i), i);
		}
		if (root.getOperator() == IBinaryOpInstruction.Operator.DIV && facts.operandsOf(v).size() == 2) {
			rank.put(facts.operandsOf(v).get(1), -1);
		}
		int[] best = null;
		long[] bestKey = null;
		for (int[] r : rows) {
			Integer n = rank.get(r[1]);
			Integer d = rank.get(r[2]);
			if (r[0] != v || n == null || d == null || r[1] == v || r[2] <= 0) {
				continue;
			}
			long[] key = { r[3], Math.max(n, 0), d };
			if (bestKey == null || key[0] < bestKey[0] || (key[0] == bestKey[0]
					&& (key[1] < bestKey[1] || (key[1] == bestKey[1] && key[2] < bestKey[2])))) {
				best = r;
				bestKey = key;
			}
		}
		if (best == null) {
			return null;
		}
		return new Found(best[1], facts.productFactors(best[1]), best[2], facts.dead(best[3]));
	}

	/** Runs the rules on the facts; the {@code IntentVal} rows as {site, numerator, divisor, condition}. */
	private static List<int[]> egglog(String facts) throws IOException, InterruptedException {
		Path program = Files.createTempFile("phase1-", ".egg");
		Path out = Files.createTempFile("phase1-", ".out");
		Path err = Files.createTempFile("phase1-", ".err");
		try {
			Files.writeString(program, rules() + facts + RUN, StandardCharsets.UTF_8);
			Process p = new ProcessBuilder(EGGLOG, program.toString())
					.redirectOutput(out.toFile()).redirectError(err.toFile()).start();
			if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				throw new IOException("egglog ran longer than " + TIMEOUT_SECONDS + " s");
			}
			if (p.exitValue() != 0) {
				List<String> lines = Files.readAllLines(err, StandardCharsets.UTF_8);
				throw new IOException("egglog exit " + p.exitValue() + ": "
						+ String.join(" | ", lines.subList(Math.max(0, lines.size() - 3), lines.size())));
			}
			List<int[]> rows = new ArrayList<>();
			for (String line : Files.readAllLines(out, StandardCharsets.UTF_8)) {
				if (line.startsWith("IntentVal,")) {
					String[] f = line.split(",");
					rows.add(new int[] { Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
							Integer.parseInt(f[4]) });
				}
			}
			return rows;
		} finally {
			Files.deleteIfExists(program);
			Files.deleteIfExists(out);
			Files.deleteIfExists(err);
		}
	}

	private static synchronized String rules() throws IOException {
		if (rules == null) {
			Path dir = Paths.get(RULES_DIR);
			rules = Files.readString(dir.resolve("rules.egg"), StandardCharsets.UTF_8) + "\n"
					+ Files.readString(dir.resolve("phase1.egg"), StandardCharsets.UTF_8) + "\n";
		}
		return rules;
	}

	private static synchronized void log(String method, int candidates, int ceilings, long start, String status) {
		if (LOG == null) {
			return;
		}
		String line = method + "\t" + candidates + "\t" + ceilings + "\t" + (System.nanoTime() - start) / 1_000_000
				+ "\t" + status.replace('\n', ' ').replace('\t', ' ') + "\n";
		try {
			Files.writeString(Paths.get(LOG), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		} catch (IOException e) {
			System.err.println("egglogPhase1: cannot write " + LOG + ": " + e);
		}
	}
}

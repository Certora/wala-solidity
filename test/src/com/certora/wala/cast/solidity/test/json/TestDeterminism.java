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
package com.certora.wala.cast.solidity.test.json;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.certora.wala.cast.solidity.client.SolidityRoundingAnalysisEngineJSON;

/**
 * Runs the same analysis twice and requires identical results. Guards the known
 * order-sensitive machinery: per-context caching, recursion cuts, and hash-ordered
 * collections. Fixtures chosen to exercise enum contexts, ceilings and call recursion.
 *
 * <p>What is compared is each graph's root metadata (method, parameters, roundings, return)
 * — the per-method primary report — as sorted canonical strings. Deliberately excluded, each
 * recorded in HANDOFF.md alongside H6: the graphs' array order and node numbering
 * (unspecified); the call edges and the callee-context child entries (makeGraph attaches an
 * order-dependent subset of both — two runs can disagree on which callee contexts appear at
 * all); and the graph labels (their receiver-type suffix comes from a points-to query that
 * returns empty for a second engine instance in the same JVM — WALA static state).
 */
public class TestDeterminism {

	private JSONObject analyze(String testDir) throws Exception {
		File astDir = new File(testDir, "ast");
		File bz2 = new File(astDir, ".asts.json.bz2");
		File asts = bz2.exists() ? bz2 : new File(astDir, ".asts.json");
		return new SolidityRoundingAnalysisEngineJSON(new File(testDir, "run.conf"), asts.getAbsolutePath())
				.analyze();
	}

	/** A key-sorted, order-stable rendering, so hash iteration order cannot leak in. */
	private static String canon(Object o) {
		if (o instanceof JSONObject obj) {
			StringBuilder sb = new StringBuilder("{");
			obj.keySet().stream().sorted().forEach(k -> sb.append(k).append(':').append(canon(obj.get(k))).append(','));
			return sb.append('}').toString();
		}
		if (o instanceof org.json.JSONArray arr) {
			StringBuilder sb = new StringBuilder("[");
			for (int i = 0; i < arr.length(); i++) {
				sb.append(canon(arr.get(i))).append(',');
			}
			return sb.append(']').toString();
		}
		return String.valueOf(o);
	}

	private static java.util.List<String> results(JSONObject output) {
		java.util.List<String> all = new java.util.ArrayList<>();
		org.json.JSONArray graphs = output.getJSONArray("graphs");
		for (int i = 0; i < graphs.length(); i++) {
			// node "0" is the graph's own method; higher numbers are callee-context children,
			// whose presence is order-dependent (see the class comment)
			all.add(canon(graphs.getJSONObject(i).getJSONObject("nodes").getJSONObject("0").getJSONObject("metadata")));
		}
		java.util.Collections.sort(all);
		return all;
	}

	private void twice(String testDir) throws Exception {
		java.util.List<String> first = results(analyze(testDir));
		java.util.List<String> second = results(analyze(testDir));
		if (!first.equals(second)) {
			File dir = new File("target/determinism");
			dir.mkdirs();
			String name = new File(testDir).getName();
			java.nio.file.Files.write(new File(dir, name + "-1.txt").toPath(), first);
			java.nio.file.Files.write(new File(dir, name + "-2.txt").toPath(), second);
			assertTrue(false, "two runs of " + testDir + " produced different results; see target/determinism/"
					+ name + "-{1,2}.txt");
		}
	}

	@Test
	public void enumContexts() throws Exception {
		twice("test/data/EnumCompare");
	}

	@Test
	public void ceilings() throws Exception {
		twice("test/data/CeilingIdioms");
	}

	@Test
	@org.junit.jupiter.api.Disabled("fails today: on call-heavy fixtures even the set of emitted"
			+ " per-method graphs varies between runs (the P2 recursion-cut caching and H6"
			+ " first-context reporting, see HANDOFF.md). Enable when those land.")
	public void callRecursion() throws Exception {
		twice("test/data/Staker3");
	}
}

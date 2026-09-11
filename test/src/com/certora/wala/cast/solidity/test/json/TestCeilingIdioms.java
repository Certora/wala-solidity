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

import org.json.JSONArray;

import com.jayway.jsonpath.DocumentContext;

/**
 * The ways integer code computes {@code ceil(N / D)} without a branch (Yul comparison and
 * double-iszero indicators, an indicator returned by a call with the {@code mulmod} builtin, and
 * masked or guarded {@code (N - 1) / D + 1}) are Up; near misses that are not ceilings stay Down.
 */
public class TestCeilingIdioms extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/CeilingIdioms";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		for (String fn : new String[] { "yulGt", "yulIsZero", "mulDivUp", "ceilDiv", "divUp" }) {
			expect(jsonParser, fn, "Up");
		}
		for (String fn : new String[] { "wrongPolarity", "unguardedPredecrement", "plusConstant" }) {
			expect(jsonParser, fn, "Down");
		}
	}

	private void expect(DocumentContext jsonParser, String fn, String direction) {
		JSONArray all = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + fn + ">')].return");
		System.err.println(fn + ": " + all);
		assert !all.isEmpty() : fn + " not analyzed";
		for (Object r : all) {
			assert direction.equals(r) : fn + " should return " + direction + ", got " + all;
		}
	}
}

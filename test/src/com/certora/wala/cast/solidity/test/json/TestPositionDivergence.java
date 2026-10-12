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
 * A binary search whose low and high are positions. With an exact key both runs read the same
 * element, so the result is exact. With a rounded key (t / d) the guard keys[mid] < key rounds,
 * the two runs can move low differently and read different elements (t = 5, d = 2,
 * keys = [1, 2, 3, 4]: the integer run returns vals[1], the real run vals[2]), so the result is
 * Inconsistent. Positions used to be pinned exact even when merged under a rounded guard.
 */
public class TestPositionDivergence extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/PositionDivergence";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expect(jsonParser, "lookupExact", "Neither");
		expect(jsonParser, "lookupRounded", "Inconsistent");
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

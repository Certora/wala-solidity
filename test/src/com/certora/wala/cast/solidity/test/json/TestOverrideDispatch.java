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
 * An inherited call must dispatch to the most-derived override. The fixture mirrors
 * Royco's quoters: each rate is overridden at three levels and again in {@code Top}, with
 * a sibling base, and every overridden level rounds while Top's rate is exact. The
 * override filter used to be inverted, keeping overridden middle levels as candidates and
 * taking the first in hash order, so the verdict of {@code convert*} depended on the build
 * (Royco's {@code jtConvertNAVUnitsToTrancheUnits} came out Down or Indet).
 */
public class TestOverrideDispatch extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/OverrideDispatch";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		for (String f : new String[] { "convertA", "convertB", "convertC" }) {
			expectOnly(jsonParser, f, "Neither");
		}
	}

	/** Every reported result of {@code function} is {@code direction}, and there is one. */
	private void expectOnly(DocumentContext jsonParser, String function, String direction) {
		String all = "$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">')]";
		JSONArray any = jsonParser.read(all);
		JSONArray other = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function "
				+ function + ">' && @.return != '" + direction + "')]");
		assert !any.isEmpty() : "no result for " + function;
		assert other.isEmpty() : "expected every " + function + " to return " + direction + ", got: "
				+ jsonParser.read(all + ".return");
	}
}

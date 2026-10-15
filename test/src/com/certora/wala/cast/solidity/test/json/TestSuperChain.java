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
 * A library call chained directly on a super-call result, {@code super.f(x).half()},
 * must dispatch normally: only a callee that IS a super member access gets
 * {@code Dispatch.SPECIAL}. The translator decides this from the callee's source text,
 * and a prefix test ({@code startsWith("super.")}) wrongly marked the chained call,
 * whose SPECIAL resolution then walked an empty self points-to set and dropped the
 * call, laundering its rounding into an assumed-exact value. The Aave v3 and Radiant
 * aTokens write {@code balanceOf} exactly this way ({@code super.balanceOf(user).rayMul(...)}).
 */
public class TestSuperChain extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/SuperChain";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expectReturn(jsonParser, "f", "Down");
		expectReturn(jsonParser, "g", "Down");
	}

	private void expectReturn(DocumentContext jsonParser, String function, String direction) {
		JSONArray result = jsonParser.read(
			"$.graphs[?(@.nodes['0'].metadata.method == '<Code body of function " + function
				+ ">' && @.nodes['0'].metadata.return == '" + direction + "')]");
		assert !result.isEmpty() : "expected " + function + " to return " + direction;
	}
}

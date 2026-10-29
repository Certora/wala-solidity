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
 * The round-up bias {@code (A + D - 1) / D} is ceil(A / D), however the bias is spelled and
 * whether A is a parameter, a product or a sum. Sharing the divisor with a dividend addend is not
 * enough: {@code (a + b) / b}, the counterexample Z3 found for the old rule, rounds Down, as do a
 * bias one too small and a divisor that enters the dividend scaled.
 */
public class TestBiasDivision extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/BiasDivision";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expectReturn(jsonParser, "biasLeft", "Up");
		expectReturn(jsonParser, "biasGrouped", "Up");
		expectReturn(jsonParser, "biasFirst", "Up");
		expectReturn(jsonParser, "biasProduct", "Up");
		expectReturn(jsonParser, "biasSum", "Up");
		expectReturn(jsonParser, "biasCompoundDivisor", "Up");
		expectReturn(jsonParser, "sharedOnly", "Down");
		expectReturn(jsonParser, "biasTooSmall", "Down");
		expectReturn(jsonParser, "sharedScaled", "Down");
	}

	private void expectReturn(DocumentContext jsonParser, String function, String direction) {
		JSONArray result = jsonParser.read(
			"$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">' && @.return == '"
				+ direction + "')]");
		assert !result.isEmpty() : "expected " + function + " to return " + direction + ", got: "
			+ jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">')].return");
	}
}

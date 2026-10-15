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
 * Complementing or negating a rounded-down quotient rounds up: {@code ~x} is {@code -x - 1}
 * and {@code -x} mirrors around zero, so both flip the direction. Covers the Solidity
 * operators and Yul's {@code not}, which used to lower as logical negation.
 */
public class TestBitNot extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/BitNot";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expectReturn(jsonParser, "solNot", "Up");
		expectReturn(jsonParser, "yulNot", "Up");
		expectReturn(jsonParser, "negQuotient", "Up");
	}

	private void expectReturn(DocumentContext jsonParser, String function, String direction) {
		JSONArray result = jsonParser.read(
			"$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">' && @.return == '"
				+ direction + "')]");
		assert !result.isEmpty() : "expected " + function + " to return " + direction + ", got: "
			+ jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">')].return");
	}
}

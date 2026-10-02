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
 * The Yul arithmetic builtins translate to real operations instead of silently severing
 * dataflow into a null constant. Shifts and sdiv round down; xor of a rounded operand is
 * Inconsistent (it would have been invisible before). exp, addmod and the 0/1 comparisons
 * come out exact: that locks the present POW and remainder treatment (see the soundness
 * ledger), so a change to it is deliberate rather than accidental.
 */
public class TestYulBuiltins extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/YulBuiltins";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expectReturn(jsonParser, "shlQuotient", "Down");
		expectReturn(jsonParser, "sarQuotient", "Down");
		expectReturn(jsonParser, "sdivQuotient", "Down");
		expectReturn(jsonParser, "xorRounded", "Inconsistent");
		expectReturn(jsonParser, "expQuotient", "Neither");
		expectReturn(jsonParser, "addmodPass", "Neither");
		expectReturn(jsonParser, "sltIndicator", "Neither");
	}

	private void expectReturn(DocumentContext jsonParser, String function, String direction) {
		JSONArray result = jsonParser.read(
			"$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">' && @.return == '"
				+ direction + "')]");
		assert !result.isEmpty() : "expected " + function + " to return " + direction + ", got: "
			+ jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">')].return");
	}
}

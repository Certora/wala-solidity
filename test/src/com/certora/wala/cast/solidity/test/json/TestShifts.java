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
 * Right shifts are floor divisions by a power of two, and Yul's {@code shr} takes the shift
 * amount first: {@code shr(s, v)} is {@code v >> s}. All three shapes round down, including
 * the one whose shifted value is itself a rounded quotient, which the reversed operand order
 * used to turn into Inconsistent.
 */
public class TestShifts extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/Shifts";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expectReturn(jsonParser, "yulShiftProduct", "Down");
		expectReturn(jsonParser, "yulShiftQuotient", "Down");
		expectReturn(jsonParser, "solShift", "Down");
	}

	private void expectReturn(DocumentContext jsonParser, String function, String direction) {
		JSONArray result = jsonParser.read(
			"$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">' && @.return == '"
				+ direction + "')]");
		assert !result.isEmpty() : "expected " + function + " to return " + direction + ", got: "
			+ jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">')].return");
	}
}

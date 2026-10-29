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
 * pay: {@code x = y / z; if (x <= minPayment) x = minPayment + 1; return x;}. The rounded guard
 * makes the integer and real runs take different arms in the sub-unit gap, where the written
 * value minPayment+1 overshoots the real quotient. Up in that region conflicts with Down
 * elsewhere, so the return is Inconsistent (a control-flow divergence).
 */
public class TestSnippet1 extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/Snippet1";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		JSONArray result = jsonParser.read(
			"$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function pay>' && @.return == 'Inconsistent')]");
		System.err.println(result);
		assert !result.isEmpty() : "expected pay to return Inconsistent, got: "
			+ jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function pay>')].return");
	}
}

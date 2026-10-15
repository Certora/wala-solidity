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
 * pay: {@code x = y / z; if (x <= minPayment) x = minPayment; return x;}. Same rounded guard as
 * Snippet1, but the clamped value minPayment never exceeds the real quotient in the divergence
 * gap, so the divergence contributes Down, consistent with the aligned Down: the return is Down.
 */
public class TestSnippet2 extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/Snippet2";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		JSONArray result = jsonParser.read(
			"$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function pay>' && @.return == 'Down')]");
		System.err.println(result);
		assert !result.isEmpty() : "expected pay to return Down, got: "
			+ jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function pay>')].return");
	}
}

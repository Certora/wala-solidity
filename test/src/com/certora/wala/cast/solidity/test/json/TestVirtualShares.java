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
 * The round-up bias is a shared expression between dividend and divisor, not a shared value
 * number. The ERC4626 virtual-shares floor shares only the interned literal 1, and the
 * storage-fee variant shares only the contract reference behind two reads: both round Down.
 * The genuine idiom, where the divisor itself flows into the dividend's addition, stays Up.
 */
public class TestVirtualShares extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/VirtualShares";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expectReturn(jsonParser, "convertToShares", "Down");
		expectReturn(jsonParser, "withFee", "Down");
		expectReturn(jsonParser, "biasDiv", "Up");
	}

	private void expectReturn(DocumentContext jsonParser, String function, String direction) {
		JSONArray result = jsonParser.read(
			"$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">' && @.return == '"
				+ direction + "')]");
		assert !result.isEmpty() : "expected " + function + " to return " + direction + ", got: "
			+ jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">')].return");
	}
}

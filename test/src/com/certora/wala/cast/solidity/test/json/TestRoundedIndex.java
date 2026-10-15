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
 * Reads through a computed index. When the index may round and also reaches an amount, the
 * integer and intended runs read different cells, so the loaded value is Inconsistent and
 * poisons what it feeds. A pure index only names a cell: both runs read the same one, and
 * the loaded value stays an ordinary unknown.
 */
public class TestRoundedIndex extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/RoundedIndex";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expectReturn(jsonParser, "pick", "Inconsistent");
		expectReturn(jsonParser, "probe", "Neither");
	}

	private void expectReturn(DocumentContext jsonParser, String function, String direction) {
		JSONArray result = jsonParser.read(
			"$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">' && @.return == '"
				+ direction + "')]");
		assert !result.isEmpty() : "expected " + function + " to return " + direction + ", got: "
			+ jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">')].return");
	}
}

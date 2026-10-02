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
 * Branches guarded by comparing an enum parameter the calling context pins to a constant.
 * Against a different literal the equality never holds, the round-up arm is dead, and the
 * result is the surviving round-down division. Against anything the analysis cannot pin
 * down (storage, another parameter, a call result) the equality may hold, so neither arm
 * may be pruned and the result covers both directions.
 */
public class TestEnumCompare extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/EnumCompare";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expectReturn(jsonParser, "callNever", "Down");
		expectReturn(jsonParser, "callStored", "Either");
		expectReturn(jsonParser, "callParam", "Either");
		expectReturn(jsonParser, "callPick", "Either");
	}

	private void expectReturn(DocumentContext jsonParser, String function, String direction) {
		JSONArray result = jsonParser.read(
			"$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">' && @.return == '"
				+ direction + "')]");
		assert !result.isEmpty() : "expected " + function + " to return " + direction + ", got: "
			+ jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">')].return");
	}
}

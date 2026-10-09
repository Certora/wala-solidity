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
 * Guards the enum-context plumbing for an enum in the LAST parameter position.
 * {@code EnumValueContextSelector} reads the builder's compact key array (receiver first,
 * then one slot per relevant parameter in ascending order); if a WALA upgrade changes that
 * convention to position-indexed arrays, the constant lands on the wrong parameter and both
 * wrappers degrade to Inconsistent. See the external-audit triage (B1) in HANDOFF.md.
 */
public class TestEnumThirdParam extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/EnumThirdParam";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expectReturn(jsonParser, "convUp", "Up");
		expectReturn(jsonParser, "convDown", "Down");
	}

	private void expectReturn(DocumentContext jsonParser, String function, String direction) {
		JSONArray result = jsonParser.read(
			"$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">' && @.return == '"
				+ direction + "')]");
		assert !result.isEmpty() : "expected " + function + " to return " + direction + ", got: "
			+ jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">')].return");
	}
}

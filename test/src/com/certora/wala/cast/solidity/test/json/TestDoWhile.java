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
 * pay: {@code bound = y / z; do { s += 1; i += 1; } while (i < bound); return s;}. The do-while is
 * lowered by peeling its first iteration into {@code body; while (i < bound) body}, after which
 * the loop's trip count follows the rounded-down bound as in Snippet3: the return is Down.
 */
public class TestDoWhile extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/DoWhile";
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

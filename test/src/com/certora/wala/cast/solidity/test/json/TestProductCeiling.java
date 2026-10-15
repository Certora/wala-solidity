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
 * The branch-form predecrement ceiling {@code x == 0 ? 0 : (x - 1) / y + 1} must be
 * recognized when the dividend {@code x} is itself a product, as in Balancer's
 * {@code FixedPoint.mulUp}: the guard tests the product against zero, not its factors,
 * so {@code nonzeroAt} has to fall through to the dominating-guard scan when the
 * factors alone cannot be proven nonzero. Before that fix the whole family silently
 * reported Down, the opposite of the developer's named intent.
 */
public class TestProductCeiling extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/ProductCeiling";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expectReturn(jsonParser, "mulUp", "Up");
		expectReturn(jsonParser, "mulUpFlipped", "Up");
		expectReturn(jsonParser, "mulUpNoCheck", "Up");
		expectReturn(jsonParser, "mulUpParamDiv", "Up");
		expectReturn(jsonParser, "divUpTrailing", "Up");
		expectReturn(jsonParser, "divUp", "Up");
	}

	private void expectReturn(DocumentContext jsonParser, String function, String direction) {
		JSONArray result = jsonParser.read(
			"$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">' && @.return == '"
				+ direction + "')]");
		assert !result.isEmpty() : "expected " + function + " to return " + direction + ", got: "
			+ jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + function + ">')].return");
	}
}

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
 * OpenZeppelin's {@code mulDiv} with a rounding mode, in the v5 shape, {@code mulDiv(x, y, d) + SafeCast.toUint(unsignedRoundsUp(rounding) && mulmod(x, y, d) > 0)}.
 * A constant mode makes it the floor or the ceiling; a mode that is a free input makes it
 * either, so the direction is Inconsistent. It used to be Down, because the ceiling idiom was
 * dropped whenever its guard was not constantly true (EigenLayer, EulerEarn).
 */
public class TestRoundingFlag extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/RoundingFlag";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expect(jsonParser, "v5Free", "Inconsistent");
		expect(jsonParser, "v5Floor", "Down");
		expect(jsonParser, "v5Ceil", "Up");
	}

	private void expect(DocumentContext jsonParser, String fn, String direction) {
		JSONArray all = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + fn + ">')].return");
		assert !all.isEmpty() : fn + " not analyzed";
		for (Object r : all) {
			assert direction.equals(r) : fn + " should return " + direction + ", got " + all;
		}
	}
}

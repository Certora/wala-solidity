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
 * OpenZeppelin's {@code mulDiv} with a rounding mode, in the v4.9 shape, {@code if (rounding == Rounding.Up && mulmod(x, y, d) > 0) result += 1}.
 * A constant mode makes it the floor or the ceiling; a mode that is a free input makes it
 * either, so the direction is Inconsistent. It used to be Down, because the ceiling idiom was
 * dropped whenever its guard was not constantly true (EigenLayer, EulerEarn).
 */
public class TestRoundingFlagV4 extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/RoundingFlagV4";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expect(jsonParser, "v4Free", "Inconsistent");
		expect(jsonParser, "v4Down", "Down");
		expect(jsonParser, "v4Up", "Up");
	}

	private void expect(DocumentContext jsonParser, String fn, String direction) {
		JSONArray all = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + fn + ">')].return");
		assert !all.isEmpty() : fn + " not analyzed";
		for (Object r : all) {
			assert direction.equals(r) : fn + " should return " + direction + ", got " + all;
		}
	}
}

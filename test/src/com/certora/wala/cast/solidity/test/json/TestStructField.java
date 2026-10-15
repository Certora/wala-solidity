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
 * A struct member keeps the direction of the value written to it. A struct constructor used to be
 * translated as a cast of its first argument and a member read was unmodelled, so
 * {@code Exp({mantissa: rate()}).mantissa} read as exact: dividing by it looked like a plain
 * floor (Down) where dividing by a Down rate is Inconsistent (Compound's ExponentialNoError).
 * Constructors are now one write per member, matched by name, and a read meets the writes
 * that can reach it.
 */
public class TestStructField extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/StructField";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expect(jsonParser, "inline_", "Inconsistent");
		expect(jsonParser, "localStruct", "Inconsistent");
		expect(jsonParser, "helperStruct", "Inconsistent");
		expect(jsonParser, "namedLo", "Neither");
		expect(jsonParser, "namedHi", "Down");
		expect(jsonParser, "positionalHi", "Down");
		expect(jsonParser, "written", "Down");
		expect(jsonParser, "beforeWrite", "Neither");
		expect(jsonParser, "loopFresh", "Neither");
		expect(jsonParser, "loopCarried", "Down");
		expect(jsonParser, "returned", "Down");
		expect(jsonParser, "ceil", "Up");
	}

	private void expect(DocumentContext jsonParser, String fn, String direction) {
		JSONArray all = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + fn + ">')].return");
		assert !all.isEmpty() : fn + " not analyzed";
		for (Object r : all) {
			assert direction.equals(r) : fn + " should return " + direction + ", got " + all;
		}
	}
}

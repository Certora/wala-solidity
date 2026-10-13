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
 * A value read back from a memory array right after it was written there is aliased to the
 * written value; returning it must report the written value's direction. Before the fix the
 * returned value was dropped (its owner differed from itself), so a(i) = t / d; return a(i)
 * reported Neither instead of Down, directly or through an internal call.
 *
 * The other functions in ArrayDirections.sol document known limitations (memory array contents
 * read through a different index, writes through a rounded index, storage arrays and mappings
 * are assumed exact); they are not asserted here.
 */
public class TestArrayDirections extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/ArrayDirections";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expect(jsonParser, "memAliasedRead", "Down");
		expect(jsonParser, "helper", "Down");
		expect(jsonParser, "memAliasedReadInternal", "Down");
		expect(jsonParser, "memAliasedReadPlus", "Down");
		expect(jsonParser, "divReturn", "Down");
		expect(jsonParser, "memPlainRead", "Neither");
	}

	private void expect(DocumentContext jsonParser, String fn, String direction) {
		JSONArray all = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + fn + ">')].return");
		System.err.println(fn + ": " + all);
		assert !all.isEmpty() : fn + " not analyzed";
		for (Object r : all) {
			assert direction.equals(r) : fn + " should return " + direction + ", got " + all;
		}
	}
}

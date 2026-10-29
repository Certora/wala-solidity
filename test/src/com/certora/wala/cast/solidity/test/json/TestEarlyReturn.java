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
 * Snippet1 and Snippet2 written with early returns. Returns are lowered through a single function
 * exit, so the returned values merge like the arms of an if/else and divergence applies to them:
 * bump is Inconsistent and clamp is Down, as in the merging versions.
 */
public class TestEarlyReturn extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/EarlyReturn";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expect(jsonParser, "bump", "Inconsistent");
		expect(jsonParser, "clamp", "Down");
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

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
 * Array contents are one direction per method: a read sees every store into any array of the
 * method that may run before it (arrays alias through struct fields, nested arrays and merges),
 * and only a sole preceding store lets a read through the same index keep the stored direction.
 */
public class TestArrayContents extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/ArrayContents";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expect(jsonParser, "overwrite", "Down");
		expect(jsonParser, "overwriteRoundedIndex", "Inconsistent");
		expect(jsonParser, "viaStruct", "Down");
		expect(jsonParser, "viaMerge", "Down");
		expect(jsonParser, "viaNested", "Down");
		expect(jsonParser, "viaCopy", "Down");
		expect(jsonParser, "readThenWrite", "Neither");
		expect(jsonParser, "readThenWriteLoop", "Down");
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

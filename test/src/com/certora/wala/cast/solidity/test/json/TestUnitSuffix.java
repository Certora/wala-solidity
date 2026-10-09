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
 * A unit suffix scales a number literal: {@code 1 weeks} is 604800 and {@code 0.5 ether} is
 * 5e17. The frontend used to drop the suffix, so a division by {@code 1 weeks} was a division by
 * one and looked exact (InfiniFi's EpochLib.epoch).
 */
public class TestUnitSuffix extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/UnitSuffix";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		for (String f : new String[] { "perWeek", "perDay", "perHour", "perMinute", "inEther", "halfEther", "inGwei" }) {
			expect(jsonParser, f, "Down");
		}
		expect(jsonParser, "perSecond", "Neither");
		expect(jsonParser, "inWei", "Neither");
	}

	private void expect(DocumentContext jsonParser, String fn, String direction) {
		JSONArray all = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + fn + ">')].return");
		assert !all.isEmpty() : fn + " not analyzed";
		for (Object r : all) {
			assert direction.equals(r) : fn + " should return " + direction + ", got " + all;
		}
	}
}

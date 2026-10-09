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
import org.json.JSONObject;

import com.jayway.jsonpath.DocumentContext;

/**
 * An explicit base-contract call {@code Base.f(...)} is bound statically to Base's body, which
 * runs on the calling contract (a virtual call inside it dispatches on the caller). The call used
 * to get no target at all: the receiver {@code Base} named a type, not an object, so dispatch on
 * the function object's self found nothing and the call was dropped as exact (Royco's
 * IdenticalAssetsChainlinkToAdminOracleQuoter.getTrancheUnitToNAVUnitConversionRateWAD).
 */
public class TestBaseCall extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/BaseCall";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expect(jsonParser, "rate", "[36,", "Down");     // Derived.rate = Base.rate()
		expect(jsonParser, "scaled", "[40,", "Down");   // Derived.scaled = Base.scaled(x) + 1
		expect(jsonParser, "useRate", "[44,", "Inconsistent");
		expect(jsonParser, "useScaled", "[48,", "Down");
		expect(jsonParser, "total", "[57,", "Down");    // Base.total() reaches Derived.share
		expect(jsonParser, "viaOther", "[61,", "Neither");
	}

	private void expect(DocumentContext jsonParser, String fn, String line, String direction) {
		JSONArray all = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + fn + ">')]");
		int seen = 0;
		for (int i = 0; i < all.length(); i++) {
			JSONObject md = all.getJSONObject(i);
			if (md.getString("methodPosition").contains(":" + line)) {
				seen++;
				assert direction.equals(md.get("return")) : fn + " should return " + direction + ", got " + md.get("return");
			}
		}
		assert seen > 0 : fn + " at " + line + " not analyzed";
	}
}

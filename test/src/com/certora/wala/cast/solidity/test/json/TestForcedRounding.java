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

import com.certora.wala.cast.solidity.test.AbstractTestForcedRounding;
import com.jayway.jsonpath.DocumentContext;

public class TestForcedRounding extends AbstractJsonTest implements AbstractTestForcedRounding {

	@Override
	protected String testDir() {
		return "test/data/ForcedRounding";
	}

	/**
	 * Both functions compute {@code x / 1} and are reported Down. {@code forcedUp}'s trailing
	 * {@code y = y + 0} is a copy, not a ceiling adjustment: it used to be reported Up by a heuristic
	 * that read any addition after a division as rounding up, which is unsound in general
	 * ({@code x / 3 + 0} rounds down). Up is only inferred from a recognized ceiling idiom.
	 */
	@Override
	public void checkResult(DocumentContext jsonParser) {
		for (String fn : new String[] {"forcedDown", "forcedUp"}) {
			JSONArray result = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + fn + ">' && @.return == 'Down') ]");
			System.err.println(result);
			assert !result.isEmpty() : fn + " should round Down";

			result = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + fn + ">' && @.return != 'Down') ]");
			System.err.println(result);
			assert result.isEmpty() : fn + " should only round Down";
		}
	}

	
}

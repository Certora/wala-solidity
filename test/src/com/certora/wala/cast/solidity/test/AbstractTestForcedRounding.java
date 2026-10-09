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
package com.certora.wala.cast.solidity.test;

import org.json.JSONArray;

import com.jayway.jsonpath.DocumentContext;

public interface AbstractTestForcedRounding extends CheckResult {

	default void checkResult(DocumentContext jsonParser) {
		// forcedDown computes x / 1 and forcedUp x / 1 + 0: division by 1 is exact, and adding the
		// same constant to both runs cannot make a result round, so both are Neither
		for (String fn : new String[] {"forcedDown", "forcedUp"}) {
			JSONArray result = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + fn + ">' && @.return == 'Neither')]");
			assert !result.isEmpty();

			result = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + fn + ">' && @.return != 'Neither')]");
			assert result.isEmpty();
		}

		// roundedDown computes x / 3, which rounds down
		JSONArray result = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function roundedDown>' && @.return == 'Down')]");
		assert !result.isEmpty();
	}
}

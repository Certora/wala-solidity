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
		// forcedDown should round Down
		JSONArray result = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function forcedDown>' && @.return == 'Down')]");
		assert !result.isEmpty();

		// forcedDown should NOT round Up
		result = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function forcedDown>' && @.return == 'Up')]");
		assert result.isEmpty();

		// forcedUp should round Up
		result = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function forcedUp>' && @.return == 'Up')]");
		assert !result.isEmpty();

		// forcedUp should NOT round Down
		result = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function forcedUp>' && @.return == 'Down')]");
		assert result.isEmpty();
	}
}

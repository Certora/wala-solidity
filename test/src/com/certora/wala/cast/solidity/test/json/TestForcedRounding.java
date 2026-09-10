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

	@Override
	public void checkResult(DocumentContext jsonParser) {
		JSONArray result = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function forcedDown>' && @.return == 'Down') ]");		
		System.err.println(result);
		assert !result.isEmpty();

		result = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function forcedDown>' && @.return != 'Down') ]");		
		System.err.println(result);
		assert result.isEmpty();

		result = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function forcedUp>' && @.return == 'Up') ]");		
		System.err.println(result);
		assert !result.isEmpty();

		result = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function forcedUp>' && @.return != 'Up') ]");		
		System.err.println(result);
		assert result.isEmpty();
	}

	
}

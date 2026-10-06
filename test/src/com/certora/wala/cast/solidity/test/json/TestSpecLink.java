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

import java.io.File;

import org.json.JSONArray;

import com.jayway.jsonpath.DocumentContext;

public class TestSpecLink extends AbstractJsonTest {

	@Override
	public void checkResult(DocumentContext jsonParser) {
		JSONArray result = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function computeN>' && @.return == 'Inconsistent') ]");
		assert !result.isEmpty();
		System.err.println(result);
	}

	@Override
	protected String testDir() {
		return "test/data/SpecLink";
	}

	@Override
	protected File confFile() {
		return new File(testDir(), "SpecLink.conf");
	}

	@Override
	protected File specFile() {
		return new File(testDir(), "SpecLink.json");
	}

}

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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;

import com.jayway.jsonpath.DocumentContext;

/**
 * Every component of a destructured tuple keeps its own direction. {@code (x, y) = f()} used to
 * write the whole tuple into each variable, and a call's tuple result was read as one exact
 * value, so a callee's rounding never reached its caller (Cork's {@code previewRedeem}). The
 * single-exit return lowering also wrote {@code return (y, x)} variable by variable, swapping
 * through an already-overwritten value, and left {@code return f()} unsplit.
 */
public class TestTupleDestructure extends AbstractJsonTest {

	private static final Pattern COMPONENT = Pattern.compile("Ltuple, (\\d+), <[^>]*> >=(\\w+)");

	@Override
	protected String testDir() {
		return "test/data/TupleDestructure";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		for (String f : new String[] { "t1", "t2", "t3", "t4", "t5", "t6", "n1", "n2", "r2" }) {
			expect(jsonParser, f, "{0=Down, 1=Down}");
		}
		expect(jsonParser, "r1", "{0=Neither, 1=Down}");
		expect(jsonParser, "r3", "{0=Neither, 1=Down}");
		expect(jsonParser, "m1", "Down");
		expect(jsonParser, "m2", "Down");
		expect(jsonParser, "s1", "Down");
	}

	private void expect(DocumentContext jsonParser, String fn, String expected) {
		JSONArray all = jsonParser.read("$.graphs[*].nodes[*].metadata[?(@.method == '<Code body of function " + fn + ">')].return");
		assert !all.isEmpty() : fn + " not analyzed";
		for (Object r : all) {
			String got = r.toString();
			if (got.startsWith("{")) {
				StringBuilder sb = new StringBuilder("{");
				Matcher m = COMPONENT.matcher(got);
				while (m.find()) {
					sb.append(sb.length() > 1 ? ", " : "").append(m.group(1)).append('=').append(m.group(2));
				}
				got = sb.append('}').toString();
			}
			assert expected.equals(got) : fn + " should return " + expected + ", got " + got;
		}
	}
}

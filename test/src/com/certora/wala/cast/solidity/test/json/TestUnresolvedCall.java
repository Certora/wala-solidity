package com.certora.wala.cast.solidity.test.json;

import org.json.JSONArray;
import org.json.JSONObject;

import com.jayway.jsonpath.DocumentContext;

public class TestUnresolvedCall extends AbstractJsonTest {

	@Override
	protected String testDir() {
		return "test/data/UnresolvedCall";
	}

	@Override
	public void checkResult(DocumentContext jsonParser) {
		expect(jsonParser, "viaRounded", "[25,", "Inconsistent");
		expect(jsonParser, "viaExact", "[29,", "Neither");
		expect(jsonParser, "viaPrice", "[33,", "Neither");
		expect(jsonParser, "maxWithdraw", "[38,", "Inconsistent"); // was Down
		expect(jsonParser, "scaledPrice", "[46,", "Down");
		expect(jsonParser, "narrow", "[50,", "Down");
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

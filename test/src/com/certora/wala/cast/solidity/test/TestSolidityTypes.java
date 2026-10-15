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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

import com.certora.wala.cast.solidity.types.SolidityTypes;
import com.ibm.wala.types.TypeReference;

/**
 * {@link SolidityTypes}' sized integer and bytes fields are looked up reflectively by name
 * ({@code getField("uint" + bits)}), so the compiler cannot catch a field whose
 * {@link TypeReference} names a different width than the field does. uint16/uint24 and
 * int16/int24 were swapped this way once; this pins every field to its own name.
 */
public class TestSolidityTypes {

	@Test
	public void sizedTypeNamesMatchTheirFields() throws IllegalAccessException {
		int checked = 0;
		for (Field f : SolidityTypes.class.getFields()) {
			if (!Modifier.isStatic(f.getModifiers()) || !TypeReference.class.equals(f.getType())) {
				continue;
			}
			String name = f.getName();
			if (name.matches("uint\\d+|int\\d+|bytes\\d+")) {
				TypeReference t = (TypeReference) f.get(null);
				assertEquals("P" + name, t.getName().toString(),
					"field SolidityTypes." + name + " holds the wrong type name");
				checked++;
			}
		}
		assertTrue(checked > 60, "expected to check the full sized-type table, saw " + checked);
	}
}

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
package com.certora.wala.analysis.rounding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.ibm.wala.util.collections.HashMapFactory;

public interface RoundingSummary {

	public static class Key {
		private final String functionName;
		private final List<Direction> acceptedInputs;

		public Key(String functionName, List<Direction> acceptedInputs) {
			this.functionName = functionName;
			this.acceptedInputs = acceptedInputs;
		}

		@Override
		public int hashCode() {
			return Objects.hash(acceptedInputs, functionName);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			Key other = (Key) obj;
			return Objects.equals(acceptedInputs, other.acceptedInputs)
					&& Objects.equals(functionName, other.functionName);
		}
	}

	public static class Value {
		Direction result;
		boolean isDivOp;
		
		public Value(Direction result, boolean isDivOp) {
			this.result = result;
			this.isDivOp = isDivOp;
		}
	}
	
	Value get(Key key);
	
	boolean has(Key key);

	/**
	 * True if the function is modelled as a division ({@link Value#isDivOp}): it computes
	 * {@code floor(a_1 * ... * a_k / d)} of its arguments {@code a_1, ..., a_k, d}.
	 */
	default boolean isDivOp(String functionName) {
		return false;
	}
	
	void add(Key k, Value v);

	default void add(String functionName, List<Collection<Direction>> acceptedInputs, Value v) {
		addRec(functionName, new ArrayList<>(), new ArrayList<>(acceptedInputs), v);
	}

	default void addRec(String functionName, List<Direction> list, List<Collection<Direction>> acceptedInputs, Value v) {
		if (acceptedInputs.isEmpty()) {
			add(new Key(functionName, list), v);
		} else {
			Collection<Direction> next = acceptedInputs.remove(0);
			next.forEach(d -> { 
				List<Direction> l = new ArrayList<>(list);
				l.add(d);
				addRec(functionName, l, new ArrayList<>(acceptedInputs), v);
			});
		}
	}
	
	public static class Default implements RoundingSummary {
		Map<Key,Value> summaries = HashMapFactory.make();
		
		{
			add("Lcontract Math.mulDiv (uint256,uint256,uint256) --> uint256",
				Arrays.asList(
					Arrays.asList(Direction.Neither), 
					Arrays.asList(Direction.Down, Direction.Neither), 
					Arrays.asList(Direction.Down, Direction.Neither), 
					Arrays.asList(Direction.Up, Direction.Neither)),
				new Value(Direction.Down, true));
		}
		
		@Override
		public Value get(Key key) {
			return summaries.get(key);
		}

		@Override
		public boolean has(Key key) {
			return summaries.containsKey(key);
		}

		@Override
		public boolean isDivOp(String functionName) {
			return summaries.entrySet().stream()
				.anyMatch(e -> e.getKey().functionName.equals(functionName) && e.getValue().isDivOp);
		}

		@Override
		public void add(Key k, Value v) {
			summaries.put(k, v);
		}
		
	}
}

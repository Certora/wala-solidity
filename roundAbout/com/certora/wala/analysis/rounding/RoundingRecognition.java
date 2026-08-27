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

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.certora.wala.analysis.defuse.DefUseGraph;
import com.google.common.collect.Sets;
import com.ibm.wala.shrike.shrikeBT.IBinaryOpInstruction;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.graph.NumberedGraph;
import com.ibm.wala.util.graph.impl.GraphInverter;
import com.ibm.wala.util.graph.traverse.DFS;
import com.ibm.wala.util.intset.IntSetUtil;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * Phase 1: pattern recognition. Runs once per method (uses only the IR and def-use, not
 * the analysis result) and records two things for Phase 2 to propagate:
 * 
 * - each division's direction: Down, or Up for the round-up bias {@code (a+b-1)/b}
 *     (recognized when the divisor also flows into the dividend);
 * - the ceiling idiom {@code phi(X, X+1)} where X is a Down division: its result is
 *     divUp of that division. A bare {@code a/b + c} has no such phi, so it is skipped.
 * 
 */
public class RoundingRecognition {
	private final DefUseGraph dug;
	private final Map<Integer, Direction> divDir = HashMapFactory.make();
	private final Map<Integer, int[]> roundUpIdiom = HashMapFactory.make();

	public RoundingRecognition(IR ir) {
		this.dug = new DefUseGraph(ir);
		classifyDivisions(ir);
		recognizeCeilingIdioms(ir);
	}

	/** Recognized rounding direction of a division, default is Down. */
	public Direction divDirection(SSABinaryOpInstruction div) {
		return divDir.getOrDefault(div.iIndex(), Direction.Down);
	}

	/**
	 * For a recognized ceiling-idiom phi, the {dividend, divisor} of the underlying
	 * division (Phase 2 treats the phi as divUp of these); otherwise null.
	 */
	public int[] roundUpIdiomOperands(SSAPhiInstruction phi) {
		return roundUpIdiom.get(phi.iIndex());
	}

	// division direction (the bias idiom)

	private void classifyDivisions(IR ir) {
		ir.iterateAllInstructions().forEachRemaining(inst -> {
			if (inst instanceof SSABinaryOpInstruction) {
				SSABinaryOpInstruction bin = (SSABinaryOpInstruction) inst;
				if (bin.getOperator() == IBinaryOpInstruction.Operator.DIV) {
					divDir.put(bin.iIndex(), classify(bin));
				}
			}
		});
	}

	private Direction classify(SSABinaryOpInstruction instruction) {
		Set<SSAInstruction> divisor = getDivisorRelated(instruction);

		Set<SSAInstruction> dividend = getDividendRelated(instruction);
		Set<SSAInstruction> dividendAddends = dividend.stream()
			.filter(inst -> inst instanceof SSABinaryOpInstruction && ((SSABinaryOpInstruction) inst).getOperator() == IBinaryOpInstruction.Operator.ADD)
			.map(inst -> getDeriving(inst))
			.reduce((l, r) -> Sets.union(l, r))
			.orElse(Collections.emptySet());

		MutableIntSet bothValues = getRelatedValues(instruction.getUse(1), divisor, false);
		bothValues.intersectWith(getRelatedValues(instruction.getUse(0), dividendAddends, false));

		return bothValues.isEmpty() ? Direction.Down : Direction.Up;
	}

	// remainder idiom

	private void recognizeCeilingIdioms(IR ir) {
		DefUse du = dug.du();
		SymbolTable st = ir.getSymbolTable();
		ir.iterateAllInstructions().forEachRemaining(inst -> {
			if (inst instanceof SSAPhiInstruction) {
				SSAPhiInstruction phi = (SSAPhiInstruction) inst;
				if (phi.getNumberOfUses() == 2) {
					int[] ops = matchCeilingIdiom(phi.getUse(0), phi.getUse(1), du, st);
					if (ops == null) {
						ops = matchCeilingIdiom(phi.getUse(1), phi.getUse(0), du, st);
					}
					if (ops != null) {
						roundUpIdiom.put(phi.iIndex(), ops);
					}
				}
			}
		});
	}

	/**
	 * Matches the ceiling idiom: {@code divV} is a Down division and {@code addV} is
	 * {@code divV + 1}. Returns the division's {dividend, divisor}, or null.
	 */
	private int[] matchCeilingIdiom(int addV, int divV, DefUse du, SymbolTable st) {
		SSAInstruction divDef = du.getDef(divV);
		if (!(divDef instanceof SSABinaryOpInstruction)) {
			return null;
		}
		SSABinaryOpInstruction div = (SSABinaryOpInstruction) divDef;
		if (div.getOperator() != IBinaryOpInstruction.Operator.DIV || divDirection(div) != Direction.Down) {
			return null;
		}

		SSAInstruction addDef = du.getDef(addV);
		if (!(addDef instanceof SSABinaryOpInstruction)) {
			return null;
		}
		SSABinaryOpInstruction add = (SSABinaryOpInstruction) addDef;
		if (add.getOperator() != IBinaryOpInstruction.Operator.ADD) {
			return null;
		}
		boolean plusOne = (add.getUse(0) == divV && isOne(add.getUse(1), st))
				|| (add.getUse(1) == divV && isOne(add.getUse(0), st));
		if (!plusOne) {
			return null;
		}

		return new int[] { div.getUse(0), div.getUse(1) };
	}

	private static boolean isOne(int vn, SymbolTable st) {
		return st.isIntegerConstant(vn) && st.getIntValue(vn) == 1;
	}

	// def-use helpers (shared by both patterns)

	private Set<SSAInstruction> getRelevant(SSAInstruction inst, NumberedGraph<Integer> g) {
		if (inst == null) {
			return Collections.emptySet();
		} else if (inst.hasDef()) {
			int v = inst.getDef();
			return DFS.getReachableNodes(g, Collections.singleton(v)).stream().map(i -> dug.du().getDef(i))
					.filter(instr -> instr != null).collect(Collectors.toSet());
		} else {
			return Collections.emptySet();
		}
	}

	private Set<SSAInstruction> getDeriving(SSAInstruction inst) {
		return getRelevant(inst, GraphInverter.invert(dug));
	}

	private Set<SSAInstruction> getDivisorRelated(SSABinaryOpInstruction div) {
		return getDeriving(dug.du().getDef(div.getUse(1)));
	}

	private Set<SSAInstruction> getDividendRelated(SSABinaryOpInstruction div) {
		return getDeriving(dug.du().getDef(div.getUse(0)));
	}

	private static MutableIntSet getRelatedValues(int startValue, Set<SSAInstruction> related, boolean forward) {
		return IntSetUtil.make(IntStream.concat(
				related.stream()
						.map(inst -> (forward ? IntStream.of(inst.getDef()).filter(i -> i > 0)
								: IntStream.range(0, inst.getNumberOfUses()).map(i -> inst.getUse(i))))
						.reduce((a, b) -> IntStream.concat(a, b)).orElse(IntStream.empty()),
				IntStream.of(startValue)).distinct().toArray());
	}
}

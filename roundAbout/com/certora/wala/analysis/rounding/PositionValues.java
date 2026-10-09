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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Set;

import com.certora.wala.cast.solidity.types.SolidityTypes;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Pair;

/**
 * Which values are only ever used to say <em>where</em> to read or write: an array index, a field
 * or storage reference, or arithmetic and comparisons that feed one.
 *
 * <p>Rounding directions describe amounts: how far the integer result is from the real one. A
 * position has no real counterpart to be above or below — there is no {@code array[2.5]} — so the
 * real run uses the integer index, and such a value is exact. Without this, an index computed by a
 * division (a binary search's {@code average(low, high)}) would round, and a branch on it would
 * look like the two runs diverging, even though the value loaded through it is treated as exact.
 *
 * <p>Computed by marking every value that reaches a use as a number and taking the rest: badness
 * flows backwards through arithmetic and phis, from a callee's parameters out to the arguments at
 * each call site, and from a call's result back to the callee's returned values.
 */
public class PositionValues {
	private final CallGraph CG;
	private final Set<Pair<CGNode, Integer>> numeric;
	private final Set<Pair<CGNode, Integer>> addressing;

	public PositionValues(CallGraph CG) {
		this.CG = CG;
		this.numeric = closure(true);
		this.addressing = closure(false);
	}

	/**
	 * True when the value reaches a location — an index or a reference — and never an amount. A
	 * value that reaches neither (a bare loop counter) is not a position: its rounding still shows
	 * up in how many times a loop runs.
	 */
	public boolean isPosition(CGNode n, int vn) {
		Pair<CGNode, Integer> v = Pair.make(n, vn);
		return addressing.contains(v) && !numeric.contains(v);
	}

	/** Everything flowing into an amount ({@code asNumeric}) or into a location, over the whole call graph. */
	private Set<Pair<CGNode, Integer>> closure(boolean asNumeric) {
		Set<Pair<CGNode, Integer>> found = HashSetFactory.make();
		Deque<Pair<CGNode, Integer>> worklist = new ArrayDeque<>();
		for (CGNode n : CG) {
			seed(n, asNumeric, found, worklist);
		}
		while (!worklist.isEmpty()) {
			Pair<CGNode, Integer> v = worklist.poll();
			spreadBackwards(v.fst, v.snd, found, worklist);
		}
		return found;
	}

	private void mark(CGNode n, int vn, Set<Pair<CGNode, Integer>> found, Deque<Pair<CGNode, Integer>> worklist) {
		if (vn > 0 && found.add(Pair.make(n, vn))) {
			worklist.add(Pair.make(n, vn));
		}
	}

	/**
	 * The direct uses of each kind: as an amount, a value stored into memory, returned from an
	 * entrypoint or passed to a body we do not have; as a location, an array index or the reference
	 * of a field or array access.
	 */
	private void seed(CGNode n, boolean asNumeric, Set<Pair<CGNode, Integer>> found, Deque<Pair<CGNode, Integer>> worklist) {
		IR ir = n.getIR();
		if (ir == null) {
			return;
		}
		boolean observable = CG.getEntrypointNodes().contains(n) || CG.getPredNodeCount(n) == 0;
		for (SSAInstruction inst : ir.getInstructions()) {
			if (inst == null) {
				continue;
			}
			if (asNumeric) {
				if (inst instanceof SSAPutInstruction) {
					mark(n, ((SSAPutInstruction) inst).getVal(), found, worklist);
				} else if (inst instanceof SSAArrayStoreInstruction) {
					mark(n, ((SSAArrayStoreInstruction) inst).getValue(), found, worklist);
				} else if (inst instanceof SSAReturnInstruction && observable && inst.getNumberOfUses() > 0) {
					mark(n, inst.getUse(0), found, worklist);
				} else if (inst instanceof SSAAbstractInvokeInstruction) {
					SSAAbstractInvokeInstruction call = (SSAAbstractInvokeInstruction) inst;
					boolean known = false;
					for (CGNode target : CG.getPossibleTargets(n, call.getCallSite())) {
						known |= target.getIR() != null;
					}
					if (!known) {
						// an unanalyzed callee may use any argument as an amount
						for (int i = 1; i < call.getNumberOfUses(); i++) {
							mark(n, call.getUse(i), found, worklist);
						}
					}
				}
			} else {
				if (inst instanceof SSAArrayLoadInstruction) {
					mark(n, ((SSAArrayLoadInstruction) inst).getIndex(), found, worklist);
					mark(n, ((SSAArrayLoadInstruction) inst).getArrayRef(), found, worklist);
				} else if (inst instanceof SSAArrayStoreInstruction) {
					mark(n, ((SSAArrayStoreInstruction) inst).getIndex(), found, worklist);
					mark(n, ((SSAArrayStoreInstruction) inst).getArrayRef(), found, worklist);
				} else if (inst instanceof SSAGetInstruction && !((SSAGetInstruction) inst).isStatic()
						&& !isTupleComponent(((SSAGetInstruction) inst).getDeclaredField())) {
					mark(n, ((SSAGetInstruction) inst).getRef(), found, worklist);
				} else if (inst instanceof SSAPutInstruction && !((SSAPutInstruction) inst).isStatic()) {
					mark(n, ((SSAPutInstruction) inst).getRef(), found, worklist);
				}
			}
		}
	}

	/** Everything flowing into a numeric value is itself part of that computation. */
	private void spreadBackwards(CGNode n, int vn, Set<Pair<CGNode, Integer>> found, Deque<Pair<CGNode, Integer>> worklist) {
		IR ir = n.getIR();
		DefUse du = n.getDU();
		if (ir == null || du == null) {
			return;
		}
		SSAInstruction def = du.getDef(vn);
		if (def instanceof SSAAbstractInvokeInstruction) {
			// the callee's returned values are used as an amount here
			SSAAbstractInvokeInstruction call = (SSAAbstractInvokeInstruction) def;
			for (CGNode target : CG.getPossibleTargets(n, call.getCallSite())) {
				IR callee = target.getIR();
				if (callee == null) {
					continue;
				}
				for (SSAInstruction inst : callee.getInstructions()) {
					if (inst instanceof SSAReturnInstruction && inst.getNumberOfUses() > 0) {
						mark(target, inst.getUse(0), found, worklist);
					}
				}
			}
		} else if (def instanceof SSAGetInstruction get && !get.isStatic() && isTupleComponent(get.getDeclaredField())) {
			// a tuple component is part of the tuple's value, not a read through a location
			mark(n, get.getRef(), found, worklist);
		} else if (def != null && !(def instanceof SSAGetInstruction) && !(def instanceof SSAArrayLoadInstruction)) {
			// arithmetic, phis, conversions: their operands feed the amount (a load starts a fresh value)
			for (int i = 0; i < def.getNumberOfUses(); i++) {
				mark(n, def.getUse(i), found, worklist);
			}
		}

		if (vn <= ir.getNumberOfParameters()) {
			// the argument at each call site is used as an amount inside this callee
			for (CGNode caller : iterable(CG.getPredNodes(n))) {
				for (CallSiteReference site : iterable(CG.getPossibleSites(caller, n))) {
					IR callerIR = caller.getIR();
					if (callerIR == null) {
						continue;
					}
					for (SSAAbstractInvokeInstruction call : callerIR.getCalls(site)) {
						if (vn - 1 < call.getNumberOfUses()) {
							mark(caller, call.getUse(vn - 1), found, worklist);
						}
					}
				}
			}
		}
	}

	/** A field of a Solidity tuple: a component of a multi-value result, not a memory location. */
	public static boolean isTupleComponent(FieldReference f) {
		return f.getDeclaringClass().equals(SolidityTypes.tuple);
	}

	private static <T> Iterable<T> iterable(Iterator<T> it) {
		return () -> it;
	}
}

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
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

import com.certora.wala.analysis.rounding.RoundingGraph.Node;
import com.ibm.wala.cast.ir.ssa.CAstUnaryOp;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.shrike.shrikeBT.IBinaryOpInstruction;
import com.ibm.wala.shrike.shrikeBT.IShiftInstruction;
import com.ibm.wala.shrike.shrikeBT.IUnaryOpInstruction;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACFG.BasicBlock;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInstruction.Visitor;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.graph.dominators.Dominators;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * Builds Q for one method from the IR, Phase 1's recognition tables, the position values and
 * this context's {@link Feasibility}. Nothing here reads a {@link Direction} the analysis
 * computed, so the result is shared by every direction context of the same {@code CGNode}.
 *
 * <p>The builder mirrors the equation construction the solver used to do from SSA: one node per
 * dataflow variable, default nodes in instruction order and divergence nodes after them, the
 * store/load aliasing folded into a shared owner value number. What changes is that a recognized
 * pattern's node carries its real operands (dividend factors, guard, bound, guard slice), so no
 * equation needs to be suppressed and re-injected behind the solver's back.
 */
public class RoundingGraphBuilder {
	private final IR ir;
	private final DefUse du;
	private final SymbolTable st;
	private final RoundingRecognition recognition;
	private final Feasibility feasibility;
	private final IntPredicate isPosition;

	/** Trivial store/load aliasing: load def -> stored value, and its reverse. */
	private final int[] mapping;
	private final int[] reverseMapping;
	/** The value number whose dataflow variable each value number shares. */
	private final int[] owner;

	private final Map<Integer, Node> nodeByOwner = new LinkedHashMap<>();
	private final List<Node> equationOrder = new ArrayList<>();
	/** Cone of absorbed-candidate owners per recognized-idiom root owner. */
	private final Map<Integer, Set<Integer>> coneByRoot = HashMapFactory.make();

	public RoundingGraphBuilder(CGNode n, RoundingRecognition recognition, Feasibility feasibility,
			IntPredicate isPosition) {
		this.ir = n.getIR();
		this.du = n.getDU();
		this.st = ir.getSymbolTable();
		this.recognition = recognition;
		this.feasibility = feasibility;
		this.isPosition = isPosition;

		int max = st.getMaxValueNumber();
		this.mapping = new int[max + 1];
		this.reverseMapping = new int[max + 1];
		computeAliasing();
		this.owner = new int[max + 1];
		for (int v = 1; v <= max; v++) {
			owner[v] = resolveOwner(v);
		}
	}

	public RoundingGraph build() {
		// Default nodes, in the order the SSA solver created its default equations.
		DefaultNodeFactory factory = new DefaultNodeFactory();
		for (SSAInstruction inst : ir.getInstructions()) {
			addDefault(factory, inst);
		}
		ir.iteratePhis().forEachRemaining(inst -> addDefault(factory, inst));
		ir.iteratePis().forEachRemaining(inst -> addDefault(factory, inst));
		ir.iterateCatchInstructions().forEachRemaining(inst -> addDefault(factory, inst));

		// Divergence nodes, in the order the injector registered their equations.
		ir.iterateNormalInstructions().forEachRemaining(inst -> {
			if (inst instanceof SSABinaryOpInstruction) {
				addCeiling(inst);
			}
		});
		ir.iteratePhis().forEachRemaining(inst -> {
			SSAPhiInstruction phi = (SSAPhiInstruction) inst;
			if (isPosition.test(phi.getDef()) || addCeiling(phi)) {
				return;
			}
			int o = owner[phi.getDef()];
			RoundingRecognition.GuardedMerge gm = recognition.guardedMerge(phi);
			if (gm != null) {
				put(new RoundingGraph.GuardedMerge(o, owner[gm.guardVN], owner[gm.boundVN], owner[gm.thenArmVN],
						owner[gm.elseArmVN], gm.clampOffset));
				return;
			}
			RoundingRecognition.LoopInduction li = recognition.loopInduction(phi);
			if (li != null) {
				put(new RoundingGraph.LoopMerge(o, owner[li.boundVN], owner[li.initVN], owner[li.latchVN],
						owner[li.ivInitVN], li.monotone));
				return;
			}
			RoundingRecognition.BranchFloor bf = recognition.branchFloor(phi);
			if (bf != null) {
				put(new RoundingGraph.FloorMerge(o, owners(bf.operandVNs), owners(bf.guardSliceVNs)));
			}
		});

		// Everything else: constants, parameters, and values with no equation, which keep the
		// solver's initial state exactly as an equation-less variable did.
		int max = st.getMaxValueNumber();
		for (int v = 1; v <= max; v++) {
			if (owner[v] != v || nodeByOwner.containsKey(v)) {
				continue;
			}
			if (st.isConstant(v)) {
				nodeByOwner.put(v, new RoundingGraph.Const(v));
			} else if (du.getDef(v) == null && v <= ir.getNumberOfParameters()) {
				nodeByOwner.put(v, new RoundingGraph.Param(v, v - 1));
			} else if (du.getDef(v) == null) {
				nodeByOwner.put(v, new RoundingGraph.Opaque(v, "undefined", false));
			} else {
				nodeByOwner.put(v, new RoundingGraph.Opaque(v, "unmodelled", false));
			}
		}

		Set<Integer> absorbed = absorb();

		Map<Integer, Node> byVn = HashMapFactory.make();
		for (int v = 1; v <= max; v++) {
			if (!absorbed.contains(owner[v])) {
				byVn.put(v, nodeByOwner.get(owner[v]));
			}
		}
		equationOrder.removeIf(nd -> absorbed.contains(nd.vn()));

		// A retained node must never read an absorbed value: absorption only ate scaffolding.
		for (Node nd : equationOrder) {
			for (int o : RoundingGraph.transferOperands(nd)) {
				assert byVn.containsKey(o) : "retained node " + nd + " reads absorbed v" + o;
			}
		}

		List<Integer> returns = new ArrayList<>();
		for (int v = 1; v <= max; v++) {
			if (owner[v] == v && hasReturn(v)) {
				assert !absorbed.contains(v) : "returned value v" + v + " was absorbed";
				returns.add(v);
			}
		}
		int[] returnVns = returns.stream().mapToInt(Integer::intValue).toArray();

		return new RoundingGraph(byVn, equationOrder, owner, returnVns);
	}

	private void addDefault(DefaultNodeFactory factory, SSAInstruction inst) {
		if (inst == null || !inst.hasDef()) {
			return;
		}
		Node nd = factory.get(inst);
		if (nd != null) {
			put(nd);
		}
	}

	/** The idiom node for a value that computes a ceiling here; false if it does not. */
	private boolean addCeiling(SSAInstruction inst) {
		RoundingRecognition.Ceiling c = activeCeiling(inst.getDef());
		if (c == null) {
			return false;
		}
		int root = owner[inst.getDef()];
		put(new RoundingGraph.Div(root, owners(c.dividendFactors), owner[c.divisorVN], Direction.Up, true));
		coneByRoot.put(root, cone(root, inst, c));
		return true;
	}

	private void put(Node nd) {
		Node old = nodeByOwner.put(nd.vn(), nd);
		assert old == null : "two nodes for v" + nd.vn() + ": " + old + " and " + nd;
		equationOrder.add(nd);
	}

	private int[] owners(int[] vns) {
		int[] r = new int[vns.length];
		for (int i = 0; i < vns.length; i++) {
			r[i] = owner[vns[i]];
		}
		return r;
	}

	private boolean hasReturn(int vn) {
		Iterator<SSAInstruction> is = du.getUses(vn);
		while (is.hasNext()) {
			if (is.next() instanceof SSAReturnInstruction) {
				return true;
			}
		}
		return false;
	}

	private boolean inDeadBlock(SSAInstruction inst) {
		return feasibility.deadBlocks().contains(ir.getControlFlowGraph().getBlockForInstruction(inst.iIndex()));
	}

	/**
	 * The ceiling the definition of {@code vn} computes in this context: Phase 1's, provided
	 * every phi operand it needs to be infeasible is dead here; otherwise null.
	 */
	private RoundingRecognition.Ceiling activeCeiling(int vn) {
		RoundingRecognition.Ceiling c = recognition.ceiling(vn);
		if (c == null || isPosition.test(vn)) {
			return null;
		}
		for (int[] dead : c.deadPhiOperands) {
			SSAPhiInstruction phi = (SSAPhiInstruction) du.getDef(dead[0]);
			MutableIntSet rvals = feasibility.deadPhiRvals().get(phi);
			if (rvals == null || !rvals.contains(phi.getUse(dead[1]))) {
				return null;
			}
		}
		return c;
	}

	/**
	 * Mirrors the solver's default operator dispatch: same visitor, same checks, same order.
	 * Returns the node for an instruction's default equation, or null when the instruction got
	 * none (a dead block, an unmodelled kind, or a pattern whose node is added afterwards).
	 */
	private class DefaultNodeFactory extends Visitor {
		private Node result;
		private int o;

		Node get(SSAInstruction instruction) {
			result = null;
			o = owner[instruction.getDef()];
			if (isPosition.test(instruction.getDef())) {
				return new RoundingGraph.Opaque(o, "position", true);
			}
			if (!inDeadBlock(instruction)) {
				instruction.visit(this);
			}
			return result;
		}

		@Override
		public void visitBinaryOp(SSABinaryOpInstruction instruction) {
			if (activeCeiling(instruction.getDef()) != null) {
				result = null; // the idiom node reads the dividend and divisor: added afterwards
				return;
			}
			IBinaryOpInstruction.IOperator op = instruction.getOperator();
			int l = owner[instruction.getUse(0)];
			int r = owner[instruction.getUse(1)];
			if (op == IBinaryOpInstruction.Operator.ADD) {
				result = new RoundingGraph.Add(o, l, r);

			} else if (op == IBinaryOpInstruction.Operator.MUL || op == IShiftInstruction.Operator.SHL) {
				result = new RoundingGraph.Mul(o, l, r);

			} else if (op == IBinaryOpInstruction.Operator.DIV) {
				Direction d = recognition.divDirection(instruction);
				result = new RoundingGraph.Div(o, new int[] { l }, r, d, false);

			} else if (op == IBinaryOpInstruction.Operator.SUB) {
				result = new RoundingGraph.Sub(o, l, r);

			} else if (op == IBinaryOpInstruction.Operator.AND || op == IBinaryOpInstruction.Operator.OR
					|| op == IBinaryOpInstruction.Operator.XOR) {
				result = new RoundingGraph.Bitwise(o, new int[] { l, r });

			} else if (op == IShiftInstruction.Operator.SHR || op == IShiftInstruction.Operator.USHR) {
				// x >> k is floor(x / 2^k): it rounds down, and grows as k shrinks
				result = new RoundingGraph.Div(o, new int[] { l }, r, Direction.Down, false);

			} else {
				result = new RoundingGraph.Opaque(o, "comparison", true);
			}
		}

		@Override
		public void visitUnaryOp(SSAUnaryOpInstruction instruction) {
			IUnaryOpInstruction.IOperator op = instruction.getOpcode();
			if (op == CAstUnaryOp.MINUS || op == CAstUnaryOp.BITNOT) {
				// -x mirrors x around zero and ~x is -x - 1: both flip the direction
				result = new RoundingGraph.Neg(o, owner[instruction.getUse(0)]);
			} else if (op == IUnaryOpInstruction.Operator.NEG) {
				// logical ! produces a boolean, not an amount
				result = new RoundingGraph.Opaque(o, "boolean", true);
			} else {
				result = new RoundingGraph.Assign(o, owner[instruction.getUse(0)]);
			}
		}

		@Override
		public void visitCheckCast(SSACheckCastInstruction instruction) {
			result = new RoundingGraph.Assign(o, owner[instruction.getUse(0)]);
		}

		@Override
		public void visitPhi(SSAPhiInstruction instruction) {
			// Every recognized idiom/divergence phi reads values beyond its SSA uses (division
			// operands, guard, bound), so its node is added after the defaults.
			if (activeCeiling(instruction.getDef()) != null
					|| recognition.guardedMerge(instruction) != null || recognition.loopInduction(instruction) != null
					|| recognition.branchFloor(instruction) != null) {
				result = null;
				return;
			}
			int[] arms = new int[instruction.getNumberOfUses()];
			for (int i = 0; i < arms.length; i++) {
				arms[i] = owner[instruction.getUse(i)];
			}
			result = new RoundingGraph.Merge(o, arms);
		}

		@Override
		public void visitPi(SSAPiInstruction instruction) {
			result = new RoundingGraph.Assign(o, owner[instruction.getUse(0)]);
		}

		@Override
		public void visitArrayLoad(SSAArrayLoadInstruction instruction) {
			if (mapping[instruction.getDef()] != instruction.getDef()) {
				return; // aliased to a dominating store: the stored value's node stands for both
			}
			result = new RoundingGraph.Load(o, owner[instruction.getIndex()]);
		}

		@Override
		public void visitInvoke(SSAInvokeInstruction instruction) {
			if (instruction.hasDef()) {
				result = new RoundingGraph.Call(o, instruction, null);
			}
		}

		@Override
		public void visitGet(SSAGetInstruction instruction) {
			// a component of a tuple: the value stored into a tuple built here, or that component
			// of a call's result; any other field read stays unmodelled
			FieldReference f = instruction.getDeclaredField();
			if (instruction.isStatic() || !PositionValues.isTupleComponent(f)) {
				return;
			}
			SSAInstruction def = du.getDef(instruction.getRef());
			if (def instanceof SSANewInstruction) {
				Iterator<SSAInstruction> uses = du.getUses(instruction.getRef());
				while (uses.hasNext()) {
					if (uses.next() instanceof SSAPutInstruction put && put.getRef() == instruction.getRef()
							&& put.getDeclaredField().getName().equals(f.getName())) {
						result = new RoundingGraph.Assign(o, owner[put.getVal()]);
						return;
					}
				}
			} else if (def instanceof SSAAbstractInvokeInstruction call) {
				result = new RoundingGraph.Call(o, call, f);
			}
		}
	}

	// absorption of idiom scaffolding (Option A: the graph is Q, and only Q)

	/**
	 * The owners backward-reachable from the root's SSA operands, stopping at the idiom node's
	 * own operands, constants, parameters and calls. These are the idiom's scaffolding
	 * candidates; {@link #absorb()} keeps any of them that something outside the idiom reads.
	 */
	private Set<Integer> cone(int root, SSAInstruction rootInst, RoundingRecognition.Ceiling c) {
		Set<Integer> stop = HashSetFactory.make();
		for (int f : c.dividendFactors) {
			stop.add(owner[f]);
		}
		stop.add(owner[c.divisorVN]);

		Set<Integer> cone = HashSetFactory.make();
		Deque<Integer> worklist = new ArrayDeque<>();
		for (int i = 0; i < rootInst.getNumberOfUses(); i++) {
			if (rootInst.getUse(i) > 0) {
				worklist.add(owner[rootInst.getUse(i)]);
			}
		}
		while (!worklist.isEmpty()) {
			int v = worklist.poll();
			if (v == root || stop.contains(v) || cone.contains(v) || st.isConstant(v)) {
				continue;
			}
			SSAInstruction def = du.getDef(v);
			if (def == null || def instanceof SSAAbstractInvokeInstruction) {
				continue; // parameters and calls always keep their nodes
			}
			cone.add(v);
			for (int i = 0; i < def.getNumberOfUses(); i++) {
				if (def.getUse(i) > 0) {
					worklist.add(owner[def.getUse(i)]);
				}
			}
		}
		return cone;
	}

	/**
	 * The owners whose nodes are absorbed into their idiom's node: cone members that nothing
	 * outside the idiom reads. A member escapes when a use of it (or of a value it shares its
	 * variable with) sits in an instruction that is neither absorbed nor its own idiom's root,
	 * or when a retained node lists it as an operand (a guard slice, a bound, another idiom's
	 * factors). Escapes cascade until a fixpoint.
	 */
	private Set<Integer> absorb() {
		Set<Integer> absorbed = HashSetFactory.make();
		coneByRoot.values().forEach(absorbed::addAll);
		if (absorbed.isEmpty()) {
			return absorbed;
		}

		Map<Integer, List<Integer>> membersByOwner = HashMapFactory.make();
		for (int v = 1; v < owner.length; v++) {
			if (absorbed.contains(owner[v])) {
				membersByOwner.computeIfAbsent(owner[v], k -> new ArrayList<>()).add(v);
			}
		}

		boolean changed = true;
		while (changed) {
			changed = false;
			for (Integer v : List.copyOf(absorbed)) {
				if (escapes(v, membersByOwner.get(v), absorbed)) {
					absorbed.remove(v);
					changed = true;
				}
			}
			for (Node nd : nodeByOwner.values()) {
				if (absorbed.contains(nd.vn())) {
					continue;
				}
				for (int o : RoundingGraph.transferOperands(nd)) {
					if (absorbed.remove(o)) {
						changed = true;
					}
				}
			}
		}
		return absorbed;
	}

	private boolean escapes(int v, List<Integer> members, Set<Integer> absorbed) {
		for (int member : members) {
			Iterator<SSAInstruction> uses = du.getUses(member);
			while (uses.hasNext()) {
				SSAInstruction use = uses.next();
				if (!use.hasDef()) {
					return true; // a return, store, put or branch observes the value
				}
				int w = owner[use.getDef()];
				Set<Integer> rootCone = coneByRoot.get(w);
				if (rootCone != null && rootCone.contains(v)) {
					continue; // read by its own idiom's root
				}
				if (!absorbed.contains(w)) {
					return true;
				}
			}
		}
		return false;
	}

	// trivial store/load aliasing, unchanged from the solver's variable factory

	private void computeAliasing() {
		for (int i = 1; i < mapping.length; i++) {
			mapping[i] = i;
			reverseMapping[i] = i;
		}
		Dominators<ISSABasicBlock> dom = Dominators.make(ir.getControlFlowGraph(), ir.getControlFlowGraph().entry());
		ir.iterateNormalInstructions().forEachRemaining(inst -> {
			SSAInstruction def = getDefIfAny(inst, dom);
			if (def != null) {
				def.visit(new Visitor() {
					@Override
					public void visitArrayStore(SSAArrayStoreInstruction instruction) {
						mapping[inst.getDef()] = instruction.getValue();
						reverseMapping[instruction.getValue()] = inst.getDef();
					}
				});
			}
		});
	}

	private int resolveOwner(int vn) {
		if (mapping[vn] < vn) {
			return resolveOwner(mapping[vn]);
		} else if (reverseMapping[vn] < vn) {
			return resolveOwner(reverseMapping[vn]);
		}
		return vn;
	}

	private boolean dominates(SSAInstruction before, SSAInstruction after, Dominators<ISSABasicBlock> d) {
		BasicBlock bbb = ir.getControlFlowGraph().getBlockForInstruction(before.iIndex());
		BasicBlock bba = ir.getControlFlowGraph().getBlockForInstruction(after.iIndex());
		if (bba == bbb) {
			return before.iIndex() < after.iIndex();
		} else {
			return d.isDominatedBy(bbb, bba);
		}
	}

	private SSAInstruction getDefIfAny(SSAInstruction use, Dominators<ISSABasicBlock> dom) {
		return new Visitor() {
			SSAInstruction def = null;
			boolean ok = true;

			{
				use.visit(this);
			}

			@Override
			public void visitArrayLoad(SSAArrayLoadInstruction instruction) {
				int arrayVn = instruction.getArrayRef();
				int indexVn = instruction.getIndex();
				du.getUses(arrayVn).forEachRemaining(useInst -> {
					if (useInst instanceof SSAArrayStoreInstruction) {
						if (((SSAArrayStoreInstruction)useInst).getArrayRef() == arrayVn &&
							((SSAArrayStoreInstruction)useInst).getIndex() == indexVn) {
							if (dominates(useInst, instruction, dom)) {
								if (ok) {
									if (def==null) {
										def = useInst;
									} else {
										def = null;
										ok = false;
									}
								}
							}
						}
					}
				});
			}
		}.def;
	}
}

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
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.certora.wala.analysis.defuse.DefUseGraph;
import com.google.common.collect.Sets;
import com.ibm.wala.cast.ir.ssa.CAstBinaryOp;
import com.ibm.wala.cfg.Util;
import com.ibm.wala.cfg.cdg.ControlDependenceGraph;
import com.ibm.wala.shrike.shrikeBT.IBinaryOpInstruction;
import com.ibm.wala.shrike.shrikeBT.IConditionalBranchInstruction;
import com.ibm.wala.shrike.shrikeBT.IUnaryOpInstruction;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.graph.Acyclic;
import com.ibm.wala.util.graph.NumberedGraph;
import com.ibm.wala.util.graph.dominators.Dominators;
import com.ibm.wala.util.graph.impl.GraphInverter;
import com.ibm.wala.util.graph.traverse.DFS;
import com.ibm.wala.util.intset.IBinaryNaturalRelation;
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
	private final Map<Integer, GuardedMerge> guardedMerges = HashMapFactory.make();
	private final Map<Integer, LoopInduction> loopInductions = HashMapFactory.make();
	private final Map<Integer, BranchFloor> branchFloors = HashMapFactory.make();

	public RoundingRecognition(IR ir) {
		this.dug = new DefUseGraph(ir);
		classifyDivisions(ir);
		recognizeCeilingIdioms(ir);
		recognizeGuardedMerges(ir);
		recognizeLoopInductions(ir);
		recognizeBranchFloors(ir);
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
		return roundUpIdiom.get(phi.getDef());
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
		SSACFG cfg = ir.getControlFlowGraph();
		Dominators<ISSABasicBlock> dom = Dominators.make(cfg, cfg.entry());
		DefUse du = dug.du();
		SymbolTable st = ir.getSymbolTable();
		for (ISSABasicBlock bb : cfg) {
			Iterator<SSAPhiInstruction> phis = bb.iteratePhis();
			while (phis.hasNext()) {
				SSAPhiInstruction phi = phis.next();
				if (phi.getNumberOfUses() != 2) {
					continue;
				}
				int u0 = phi.getUse(0), u1 = phi.getUse(1);
				int[] ops = matchCeilingIdiom(u0, u1, du, st);
				int addV = u0, divV = u1;
				if (ops == null) {
					ops = matchCeilingIdiom(u1, u0, du, st);
					addV = u1;
					divV = u0;
				}
				// A genuine ceiling: the +1 arm (addV) is selected exactly when the remainder of the
				// same division is nonzero. phi(x, x+1) under any other guard is not a divUp.
				if (ops != null && ceilingSelectsOnRemainder(phi, bb, ops[0], ops[1], divV, addV, ir, cfg, dom, du, st)) {
					roundUpIdiom.put(phi.getDef(), ops);
				}
			}
		}
	}

	/** True when {@code addV} (the q+1 arm) is selected exactly when {@code dividend % divisor != 0}. */
	private boolean ceilingSelectsOnRemainder(SSAPhiInstruction phi, ISSABasicBlock merge, int dividend,
			int divisor, int divV, int addV, IR ir, SSACFG cfg, Dominators<ISSABasicBlock> dom, DefUse du, SymbolTable st) {
		ISSABasicBlock gb = dom.getIdom(merge);
		SSAConditionalBranchInstruction cond = gb == null ? null : conditionalOf(gb, ir);
		if (cond == null) {
			return false;
		}
		Comparison cmp = sourceComparison(cond, du, st);
		if (cmp == null) {
			return false;
		}
		Boolean nonzeroWhenTrue = remainderNonzeroWhenTrue(cmp, dividend, divisor, du, st);
		int[] arms = trueFalseArms(phi, merge, gb, cfg, cmp, dom);
		if (nonzeroWhenTrue == null || arms == null) {
			return false;
		}
		int nonzeroArm = nonzeroWhenTrue ? arms[0] : arms[1]; // arm taken when remainder != 0
		int zeroArm = nonzeroWhenTrue ? arms[1] : arms[0]; // arm taken when remainder == 0
		return nonzeroArm == addV && zeroArm == divV;
	}

	/**
	 * Whether {@code cmp} being true means {@code dividend % divisor} is nonzero, when {@code cmp}
	 * tests that remainder against the constant 0; null if it is not such a test.
	 */
	private static Boolean remainderNonzeroWhenTrue(Comparison cmp, int dividend, int divisor, DefUse du, SymbolTable st) {
		boolean remLeft = isRemainder(cmp.left, dividend, divisor, du);
		boolean remRight = isRemainder(cmp.right, dividend, divisor, du);
		int other = remLeft ? cmp.right : cmp.left;
		if ((!remLeft && !remRight) || !(st.isIntegerConstant(other) && st.getIntValue(other) == 0)) {
			return null; // not a `remainder <op> 0` test
		}
		// The remainder is always >= 0, so `rem != 0` is equivalent to `rem > 0`.
		if (cmp.op == CAstBinaryOp.NE) return true;
		if (cmp.op == CAstBinaryOp.EQ) return false;
		if (remLeft && cmp.op == CAstBinaryOp.GT) return true; // rem > 0
		if (remLeft && cmp.op == CAstBinaryOp.LE) return false; // rem <= 0  <=>  rem == 0
		if (remRight && cmp.op == CAstBinaryOp.LT) return true; // 0 < rem
		if (remRight && cmp.op == CAstBinaryOp.GE) return false; // 0 >= rem  <=>  rem == 0
		return null;
	}

	/** Maps the two phi operands to the true / false side of the source comparison; null if unclear. */
	private int[] trueFalseArms(SSAPhiInstruction phi, ISSABasicBlock merge, ISSABasicBlock gb,
			SSACFG cfg, Comparison cmp, Dominators<ISSABasicBlock> dom) {
		List<ISSABasicBlock> preds = new ArrayList<>();
		cfg.getPredNodes(merge).forEachRemaining(preds::add);
		if (preds.size() != 2) {
			return null;
		}
		ISSABasicBlock taken = Util.getTakenSuccessor(cfg, gb);
		ISSABasicBlock notTaken = Util.getNotTakenSuccessor(cfg, gb);
		Integer trueArm = null, falseArm = null;
		for (ISSABasicBlock p : preds) {
			Boolean takenSide = predSide(p, gb, merge, taken, notTaken, dom);
			if (takenSide == null) {
				return null;
			}
			int operand = phi.getUse(Util.whichPred(cfg, p, merge));
			if (takenSide == cmp.takenMeansTrue) {
				trueArm = operand;
			} else {
				falseArm = operand;
			}
		}
		if (trueArm == null || falseArm == null) {
			return null;
		}
		return new int[] { trueArm, falseArm };
	}

	private static boolean isRemainder(int vn, int dividend, int divisor, DefUse du) {
		SSAInstruction def = du.getDef(vn);
		if (def instanceof SSABinaryOpInstruction) {
			SSABinaryOpInstruction b = (SSABinaryOpInstruction) def;
			return b.getOperator() == IBinaryOpInstruction.Operator.REM
					&& sameExpr(b.getUse(0), dividend, du) && sameExpr(b.getUse(1), divisor, du);
		}
		return false;
	}

	/** Structural equivalence: e.g. the {@code a*b} in {@code (a*b)/c} and in {@code (a*b)%c}. */
	private static boolean sameExpr(int v1, int v2, DefUse du) {
		if (v1 == v2) {
			return true;
		}
		SSAInstruction d1 = du.getDef(v1), d2 = du.getDef(v2);
		if (d1 instanceof SSABinaryOpInstruction && d2 instanceof SSABinaryOpInstruction) {
			SSABinaryOpInstruction b1 = (SSABinaryOpInstruction) d1, b2 = (SSABinaryOpInstruction) d2;
			if (b1.getOperator() == b2.getOperator() && b1.getNumberOfUses() == b2.getNumberOfUses()) {
				for (int i = 0; i < b1.getNumberOfUses(); i++) {
					if (!sameExpr(b1.getUse(i), b2.getUse(i), du)) {
						return false;
					}
				}
				return true;
			}
		}
		return false;
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

	// guarded merge (branch divergence)

	/**
	 * A recognized clamp: a two-armed merge under a guard {@code g <op> bound} where the
	 * source-false arm keeps the guarded value {@code g} (on the low side) and the source-true
	 * arm writes {@code bound + clampOffset}. Phase 2 turns this into the divergence direction;
	 * merges that are not clamps are left to {@link BranchFloor} instead.
	 */
	public static final class GuardedMerge {
		/** The guarded value, kept in the source-false arm. */
		public final int guardVN;
		/** The bound the guarded value is compared against. */
		public final int boundVN;
		/** Phi operand selected when {@code g <op> bound} is true (writes {@code bound + clampOffset}). */
		public final int thenArmVN;
		/** Phi operand selected when {@code g <op> bound} is false (keeps {@code guardVN}). */
		public final int elseArmVN;
		/** The constant k such that the source-true arm is {@code bound + k}. */
		public final int clampOffset;

		GuardedMerge(int guardVN, int boundVN, int thenArmVN, int elseArmVN, int clampOffset) {
			this.guardVN = guardVN;
			this.boundVN = boundVN;
			this.thenArmVN = thenArmVN;
			this.elseArmVN = elseArmVN;
			this.clampOffset = clampOffset;
		}
	}

	/** The clamp a phi represents, or null if it is not a recognized clamp. */
	public GuardedMerge guardedMerge(SSAPhiInstruction phi) {
		return guardedMerges.get(phi.getDef());
	}

	private void recognizeGuardedMerges(IR ir) {
		SSACFG cfg = ir.getControlFlowGraph();
		Dominators<ISSABasicBlock> dom = Dominators.make(cfg, cfg.entry());
		DefUse du = dug.du();
		SymbolTable st = ir.getSymbolTable();
		for (ISSABasicBlock bb : cfg) {
			Iterator<SSAPhiInstruction> phis = bb.iteratePhis();
			while (phis.hasNext()) {
				SSAPhiInstruction phi = phis.next();
				GuardedMerge gm = matchGuardedMerge(phi, bb, ir, cfg, dom, du, st);
				if (gm != null) {
					guardedMerges.put(phi.getDef(), gm);
				}
			}
		}
	}

	private GuardedMerge matchGuardedMerge(SSAPhiInstruction phi, ISSABasicBlock merge, IR ir, SSACFG cfg,
			Dominators<ISSABasicBlock> dom, DefUse du, SymbolTable st) {
		if (phi.getNumberOfUses() != 2) {
			return null;
		}
		// The controlling guard is the immediate dominator, which must end in a conditional.
		ISSABasicBlock gb = dom.getIdom(merge);
		if (gb == null) {
			return null;
		}
		SSAConditionalBranchInstruction cond = conditionalOf(gb, ir);
		if (cond == null) {
			return null;
		}
		Comparison cmp = sourceComparison(cond, du, st);
		if (cmp == null) {
			return null;
		}
		int[] arms = trueFalseArms(phi, merge, gb, cfg, cmp, dom);
		if (arms == null) {
			return null;
		}
		int trueArm = arms[0], falseArm = arms[1];

		// Clamp: the source-false arm keeps a compared operand g on the low side (<= / <), and
		// the source-true arm writes bound + k.
		boolean gIsLeft = falseArm == cmp.left;
		boolean gIsRight = falseArm == cmp.right;
		if ((gIsLeft || gIsRight) && lowSide(cmp.op, gIsLeft)) {
			int bound = gIsLeft ? cmp.right : cmp.left;
			Integer k = offsetFromBound(trueArm, bound, du, st);
			if (k != null) {
				return new GuardedMerge(falseArm, bound, trueArm, falseArm, k);
			}
		}
		// Not a clamp: leave it to the universal branch floor (which sound-floors any merge
		// controlled by a rounding guard, and covers non-diamond / N-way / negated shapes too).
		return null;
	}

	/** The relational comparison {@code left op right} feeding a conditional branch. */
	private static final class Comparison {
		final int left, right;
		final IBinaryOpInstruction.IOperator op;
		final boolean takenMeansTrue; // does the branch's taken edge mean (left op right) is true?

		Comparison(int left, int right, IBinaryOpInstruction.IOperator op, boolean takenMeansTrue) {
			this.left = left;
			this.right = right;
			this.op = op;
			this.takenMeansTrue = takenMeansTrue;
		}
	}

	/**
	 * Recovers the source comparison from a branch-on-boolean lowering: the branch tests a
	 * boolean {@code (left op right)} against a constant. Returns null for other shapes.
	 */
	private static Comparison sourceComparison(SSAConditionalBranchInstruction cond, DefUse du, SymbolTable st) {
		int u0 = cond.getUse(0), u1 = cond.getUse(1);
		int boolVN, constVN;
		if (st.isConstant(u1) && !st.isConstant(u0)) {
			boolVN = u0;
			constVN = u1;
		} else if (st.isConstant(u0) && !st.isConstant(u1)) {
			boolVN = u1;
			constVN = u0;
		} else {
			return null;
		}
		boolean constTrue = truthValue(st.getConstantValue(constVN));
		boolean takenMeansTrue;
		if (cond.getOperator() == IConditionalBranchInstruction.Operator.EQ) {
			takenMeansTrue = constTrue;
		} else if (cond.getOperator() == IConditionalBranchInstruction.Operator.NE) {
			takenMeansTrue = !constTrue;
		} else {
			return null;
		}

		// Unwrap boolean negations (e.g. `if (!over)`); each `!` flips the effective operator.
		int neg = 0;
		SSAInstruction def = du.getDef(boolVN);
		while (def instanceof SSAUnaryOpInstruction
				&& ((SSAUnaryOpInstruction) def).getOpcode() == IUnaryOpInstruction.Operator.NEG) {
			neg++;
			def = du.getDef(def.getUse(0));
		}
		if (!(def instanceof SSABinaryOpInstruction)) {
			return null;
		}
		SSABinaryOpInstruction b = (SSABinaryOpInstruction) def;
		if (!(b.getOperator() instanceof CAstBinaryOp)) {
			return null;
		}
		IBinaryOpInstruction.IOperator op = b.getOperator();
		if (neg % 2 == 1) {
			op = negate((CAstBinaryOp) b.getOperator());
			if (op == null) {
				return null;
			}
		}
		return new Comparison(b.getUse(0), b.getUse(1), op, takenMeansTrue);
	}

	/** The comparison operator that is logically equivalent to {@code !(a op b)}. */
	private static CAstBinaryOp negate(CAstBinaryOp op) {
		if (op == CAstBinaryOp.LE) return CAstBinaryOp.GT;
		if (op == CAstBinaryOp.GT) return CAstBinaryOp.LE;
		if (op == CAstBinaryOp.LT) return CAstBinaryOp.GE;
		if (op == CAstBinaryOp.GE) return CAstBinaryOp.LT;
		if (op == CAstBinaryOp.EQ) return CAstBinaryOp.NE;
		if (op == CAstBinaryOp.NE) return CAstBinaryOp.EQ;
		return null;
	}

	/** True when {@code (left op right)} holds precisely when g is on the small side. */
	private static boolean lowSide(IBinaryOpInstruction.IOperator op, boolean gIsLeft) {
		if (gIsLeft) {
			return op == CAstBinaryOp.LE || op == CAstBinaryOp.LT;
		} else {
			return op == CAstBinaryOp.GE || op == CAstBinaryOp.GT;
		}
	}

	/** k such that {@code arm == bound + k} for a constant k (0 when arm is bound itself); else null. */
	private Integer offsetFromBound(int arm, int bound, DefUse du, SymbolTable st) {
		if (arm == bound) {
			return 0;
		}
		SSAInstruction def = du.getDef(arm);
		if (def instanceof SSABinaryOpInstruction) {
			SSABinaryOpInstruction add = (SSABinaryOpInstruction) def;
			if (add.getOperator() == IBinaryOpInstruction.Operator.ADD) {
				if (add.getUse(0) == bound && st.isIntegerConstant(add.getUse(1))) {
					return st.getIntValue(add.getUse(1));
				}
				if (add.getUse(1) == bound && st.isIntegerConstant(add.getUse(0))) {
					return st.getIntValue(add.getUse(0));
				}
			}
		}
		return null;
	}

	private static SSAConditionalBranchInstruction conditionalOf(ISSABasicBlock bb, IR ir) {
		int last = bb.getLastInstructionIndex();
		if (last < 0) {
			return null;
		}
		SSAInstruction inst = ir.getInstructions()[last];
		return inst instanceof SSAConditionalBranchInstruction ? (SSAConditionalBranchInstruction) inst : null;
	}

	/** TRUE if p is on the taken side of gb, FALSE if the not-taken side, null if unclear. */
	private static Boolean predSide(ISSABasicBlock p, ISSABasicBlock gb, ISSABasicBlock merge,
			ISSABasicBlock taken, ISSABasicBlock notTaken, Dominators<ISSABasicBlock> dom) {
		if (p.equals(gb)) {
			if (merge.equals(taken)) {
				return Boolean.TRUE;
			}
			if (merge.equals(notTaken)) {
				return Boolean.FALSE;
			}
			return null;
		}
		boolean domTaken = dom.isDominatedBy(p, taken);
		boolean domNotTaken = dom.isDominatedBy(p, notTaken);
		if (domTaken && !domNotTaken) {
			return Boolean.TRUE;
		}
		if (domNotTaken && !domTaken) {
			return Boolean.FALSE;
		}
		return null;
	}

	private static boolean truthValue(Object constant) {
		if (constant instanceof Boolean) {
			return (Boolean) constant;
		}
		if (constant instanceof Number) {
			return ((Number) constant).longValue() != 0;
		}
		return false;
	}

	// loop trip-count (loop divergence)

	/**
	 * A loop-header phi in a loop whose exit guard {@code iv <op> bound} tests an induction
	 * variable against a loop-invariant bound. When the bound rounds, the trip count (and any
	 * value that grows with it) diverges between the integer and real runs.
	 */
	public static final class LoopInduction {
		/** The loop bound (the guard operand that is not the induction variable). */
		public final int boundVN;
		/** The controlling induction variable's initial (pre-header) value. */
		public final int ivInitVN;
		/** Monotone-increasing carried value (step > 0): its final value inherits the trip direction. */
		public final boolean monotone;
		/** This phi's pre-header operand (its own initial value). */
		public final int initVN;
		/** This phi's latch operand (used only for the exact-loop fallback). */
		public final int latchVN;

		LoopInduction(int boundVN, int ivInitVN, boolean monotone, int initVN, int latchVN) {
			this.boundVN = boundVN;
			this.ivInitVN = ivInitVN;
			this.monotone = monotone;
			this.initVN = initVN;
			this.latchVN = latchVN;
		}
	}

	/** The loop-induction fact for a header phi, or null. */
	public LoopInduction loopInduction(SSAPhiInstruction phi) {
		return loopInductions.get(phi.getDef());
	}

	private void recognizeLoopInductions(IR ir) {
		SSACFG cfg = ir.getControlFlowGraph();
		DefUse du = dug.du();
		SymbolTable st = ir.getSymbolTable();

		Map<Integer, List<Integer>> latchesByHeader = HashMapFactory.make();
		IBinaryNaturalRelation backEdges = Acyclic.computeBackEdges(cfg, cfg.entry());
		backEdges.forEach(e -> latchesByHeader.computeIfAbsent(e.getY(), h -> new ArrayList<>()).add(e.getX()));

		for (Map.Entry<Integer, List<Integer>> loop : latchesByHeader.entrySet()) {
			List<Integer> latchNums = loop.getValue();
			if (latchNums.size() != 1) {
				continue; // single-latch natural loops only (structured while/for)
			}
			ISSABasicBlock header = cfg.getNode(loop.getKey());
			ISSABasicBlock latch = cfg.getNode(latchNums.get(0));

			// The header must be a two-way merge of a single pre-header and the latch.
			List<ISSABasicBlock> preds = new ArrayList<>();
			cfg.getPredNodes(header).forEachRemaining(preds::add);
			if (preds.size() != 2) {
				continue;
			}
			ISSABasicBlock preheader = preds.get(0).equals(latch) ? preds.get(1) : preds.get(0);
			if (preheader.equals(latch)) {
				continue;
			}

			// The exit guard is a conditional in the header comparing iv against a bound.
			SSAConditionalBranchInstruction cond = conditionalOf(header, ir);
			if (cond == null) {
				continue;
			}
			Comparison cmp = sourceComparison(cond, du, st);
			if (cmp == null) {
				continue;
			}
			int latchPred = Util.whichPred(cfg, latch, header);

			Integer ivVN = null, boundVN = null;
			boolean ivIsLeft = false;
			if (inductionStepOf(cmp.left, header, latchPred, du, st) != null) {
				ivVN = cmp.left;
				boundVN = cmp.right;
				ivIsLeft = true;
			} else if (inductionStepOf(cmp.right, header, latchPred, du, st) != null) {
				ivVN = cmp.right;
				boundVN = cmp.left;
				ivIsLeft = false;
			}
			if (ivVN == null || !lowSide(cmp.op, ivIsLeft)) {
				continue; // loop must run while the induction variable is on the small side
			}

			Set<ISSABasicBlock> body = loopBody(header, latch, cfg);
			if (!loopInvariant(boundVN, body, du, cfg)) {
				continue;
			}

			int preheaderPred = Util.whichPred(cfg, preheader, header);
			// The controlling induction variable's initial value also drives the trip count:
			// starting lower (a rounded-down init) means more integer iterations.
			int ivInitVN = ((SSAPhiInstruction) du.getDef(ivVN)).getUse(preheaderPred);
			Iterator<SSAPhiInstruction> phis = header.iteratePhis();
			while (phis.hasNext()) {
				SSAPhiInstruction phi = phis.next();
				if (phi.getNumberOfUses() != 2) {
					continue;
				}
				int initVN = phi.getUse(preheaderPred);
				int latchVN = phi.getUse(latchPred);
				Integer step = inductionStep(phi.getDef(), latchVN, du, st);
				boolean monotone = step != null && step > 0;
				loopInductions.put(phi.getDef(), new LoopInduction(boundVN, ivInitVN, monotone, initVN, latchVN));
			}
		}
	}

	/** The step if {@code vn} is a header phi of {@code header} updated by a constant on the latch edge; else null. */
	private Integer inductionStepOf(int vn, ISSABasicBlock header, int latchPred, DefUse du, SymbolTable st) {
		Iterator<SSAPhiInstruction> phis = header.iteratePhis();
		while (phis.hasNext()) {
			SSAPhiInstruction phi = phis.next();
			if (phi.getDef() == vn && phi.getNumberOfUses() == 2) {
				return inductionStep(phi.getDef(), phi.getUse(latchPred), du, st);
			}
		}
		return null;
	}

	/** The constant c such that {@code latchVN == phiDef + c}; else null. */
	private Integer inductionStep(int phiDef, int latchVN, DefUse du, SymbolTable st) {
		SSAInstruction def = du.getDef(latchVN);
		if (!(def instanceof SSABinaryOpInstruction)) {
			return null;
		}
		SSABinaryOpInstruction add = (SSABinaryOpInstruction) def;
		if (add.getOperator() != IBinaryOpInstruction.Operator.ADD) {
			return null;
		}
		if (add.getUse(0) == phiDef && st.isIntegerConstant(add.getUse(1))) {
			return st.getIntValue(add.getUse(1));
		}
		if (add.getUse(1) == phiDef && st.isIntegerConstant(add.getUse(0))) {
			return st.getIntValue(add.getUse(0));
		}
		return null;
	}

	/** Blocks of the natural loop for back edge latch -> header (backward reach from latch, header included). */
	private static Set<ISSABasicBlock> loopBody(ISSABasicBlock header, ISSABasicBlock latch, SSACFG cfg) {
		Set<ISSABasicBlock> body = HashSetFactory.make();
		body.add(header);
		body.add(latch);
		Deque<ISSABasicBlock> worklist = new ArrayDeque<>();
		worklist.add(latch);
		while (!worklist.isEmpty()) {
			ISSABasicBlock n = worklist.poll();
			cfg.getPredNodes(n).forEachRemaining(m -> {
				if (!m.equals(header) && body.add(m)) {
					worklist.add(m);
				}
			});
		}
		return body;
	}

	private static boolean loopInvariant(int vn, Set<ISSABasicBlock> body, DefUse du, SSACFG cfg) {
		SSAInstruction def = du.getDef(vn);
		if (def == null) {
			return true; // parameter or constant
		}
		ISSABasicBlock b = cfg.getBlockForInstruction(def.iIndex());
		return b == null || !body.contains(b);
	}

	// universal branch floor (soundness backstop for divergence)

	/**
	 * A phi whose arms are selected by a conditional guard we could not recognize precisely
	 * (negated/compound guards, N-way merges, unrecognized loop shapes). Phase 2 floors it to
	 * Inconsistent when any value feeding a controlling guard rounds; otherwise it keeps the
	 * ordinary meet, since an exact guard makes both runs take the same path.
	 */
	public static final class BranchFloor {
		/** The phi's operands, for the ordinary meet when no controlling guard rounds. */
		public final int[] operandVNs;
		/** Non-constant values feeding the guards this phi is control-dependent on. */
		public final int[] guardSliceVNs;

		BranchFloor(int[] operandVNs, int[] guardSliceVNs) {
			this.operandVNs = operandVNs;
			this.guardSliceVNs = guardSliceVNs;
		}
	}

	/** The branch-floor fact for a phi, or null if it needs no soundness floor. */
	public BranchFloor branchFloor(SSAPhiInstruction phi) {
		return branchFloors.get(phi.getDef());
	}

	private void recognizeBranchFloors(IR ir) {
		SSACFG cfg = ir.getControlFlowGraph();
		DefUse du = dug.du();
		SymbolTable st = ir.getSymbolTable();
		ControlDependenceGraph<ISSABasicBlock> cdg = new ControlDependenceGraph<>(cfg, true);
		for (ISSABasicBlock bb : cfg) {
			Iterator<SSAPhiInstruction> phis = bb.iteratePhis();
			while (phis.hasNext()) {
				SSAPhiInstruction phi = phis.next();
				int def = phi.getDef();
				if (roundUpIdiom.containsKey(def) || guardedMerges.containsKey(def)
						|| loopInductions.containsKey(def)) {
					continue; // already handled precisely
				}
				Set<Integer> slice = controllingGuardSlice(bb, ir, cfg, cdg, du, st);
				if (slice.isEmpty()) {
					continue; // not control-dependent on any conditional: no divergence possible
				}
				int[] operands = new int[phi.getNumberOfUses()];
				for (int i = 0; i < operands.length; i++) {
					operands[i] = phi.getUse(i);
				}
				branchFloors.put(def, new BranchFloor(operands, toIntArray(slice)));
			}
		}
	}

	/** Non-constant values feeding every conditional the merge block is control-dependent on. */
	private Set<Integer> controllingGuardSlice(ISSABasicBlock merge, IR ir, SSACFG cfg,
			ControlDependenceGraph<ISSABasicBlock> cdg, DefUse du, SymbolTable st) {
		Set<ISSABasicBlock> ancestors = HashSetFactory.make();
		Deque<ISSABasicBlock> worklist = new ArrayDeque<>();
		worklist.add(merge);
		cfg.getPredNodes(merge).forEachRemaining(worklist::add);
		while (!worklist.isEmpty()) {
			ISSABasicBlock b = worklist.poll();
			if (!ancestors.add(b) || !cdg.containsNode(b)) {
				continue;
			}
			cdg.getPredNodes(b).forEachRemaining(worklist::add);
		}
		Set<Integer> slice = new LinkedHashSet<>();
		for (ISSABasicBlock a : ancestors) {
			SSAConditionalBranchInstruction cond = conditionalOf(a, ir);
			if (cond != null) {
				addSlice(cond.getUse(0), du, st, slice);
				addSlice(cond.getUse(1), du, st, slice);
			}
		}
		return slice;
	}

	/** Adds to {@code slice} every non-constant value transitively feeding {@code vn}. */
	private static void addSlice(int vn, DefUse du, SymbolTable st, Set<Integer> slice) {
		Deque<Integer> worklist = new ArrayDeque<>();
		Set<Integer> seen = HashSetFactory.make();
		worklist.add(vn);
		while (!worklist.isEmpty()) {
			int v = worklist.poll();
			if (v <= 0 || !seen.add(v) || st.isConstant(v)) {
				continue;
			}
			slice.add(v);
			SSAInstruction def = du.getDef(v);
			if (def != null) {
				for (int i = 0; i < def.getNumberOfUses(); i++) {
					if (def.getUse(i) > 0) {
						worklist.add(def.getUse(i));
					}
				}
			}
		}
	}

	private static int[] toIntArray(Set<Integer> s) {
		int[] a = new int[s.size()];
		int i = 0;
		for (int v : s) {
			a[i++] = v;
		}
		return a;
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

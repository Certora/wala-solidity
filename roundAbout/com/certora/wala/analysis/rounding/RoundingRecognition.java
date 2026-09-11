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
import java.util.Collection;
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
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
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
 * Phase 1: pattern recognition. Runs once per method (uses the IR and def-use, the bodies of
 * call targets and the library model in {@link Calls}, not the analysis result) and records, for
 * Phase 2 to propagate:
 * 
 * - each division's direction: Down, or Up for the round-up bias {@code (a+b-1)/b}
 *     (recognized when the divisor also flows into the dividend);
 * - the values that compute a ceiling {@code ceil(N/D)} (see {@link #recognizeCeilings}):
 *     Phase 2 treats them as divUp(N, D). A bare {@code a/b + c} is not one, so it is skipped;
 * - divergence facts for branch merges and loops.
 * 
 */
public class RoundingRecognition {
	private final DefUseGraph dug;
	private final Map<Integer, Direction> divDir = HashMapFactory.make();
	private final Calls calls;
	private final Map<Integer, Ceiling> ceilings = HashMapFactory.make();
	private final Map<Integer, GuardedMerge> guardedMerges = HashMapFactory.make();
	private final Map<Integer, LoopInduction> loopInductions = HashMapFactory.make();
	private final Map<Integer, BranchFloor> branchFloors = HashMapFactory.make();

	/** What recognition may know about calls: their targets' bodies, and which compute a rounded-down quotient. */
	public interface Calls {
		/** The IRs of the call's possible targets. */
		Collection<IR> targets(SSAAbstractInvokeInstruction call);

		/** True if the call computes {@code floor(a_1 * ... * a_k / d)} of its arguments {@code a_1, ..., a_k, d}. */
		boolean isFloorQuotient(SSAAbstractInvokeInstruction call);

		Calls NONE = new Calls() {
			@Override
			public Collection<IR> targets(SSAAbstractInvokeInstruction call) {
				return Collections.emptyList();
			}

			@Override
			public boolean isFloorQuotient(SSAAbstractInvokeInstruction call) {
				return false;
			}
		};
	}

	public RoundingRecognition(IR ir) {
		this(ir, Calls.NONE);
	}

	public RoundingRecognition(IR ir, Calls calls) {
		this.dug = new DefUseGraph(ir);
		this.calls = calls;
		classifyDivisions(ir);
		recognizeCeilings(ir);
		recognizeGuardedMerges(ir);
		recognizeLoopInductions(ir);
		recognizeBranchFloors(ir);
	}

	/** Recognized rounding direction of a division, default is Down. */
	public Direction divDirection(SSABinaryOpInstruction div) {
		return divDir.getOrDefault(div.iIndex(), Direction.Down);
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

	// ceiling idioms

	/**
	 * A value that computes {@code ceil(N / D)} exactly, N the product of {@code dividendFactors}
	 * and D {@code divisorVN}; Phase 2 treats it as divUp(N, D), whose real value is N / D. Some
	 * shapes compute the ceiling only on the paths a calling context makes feasible: in
	 * {@code floor + toUint(roundsUp && N % D > 0)} the guard is the remainder test only where
	 * {@code roundsUp} is constantly true. Those list the phi operands that must be infeasible.
	 */
	public static final class Ceiling {
		public final int[] dividendFactors;
		public final int divisorVN;
		/** Pairs {phi def, operand index} that must be infeasible for the value to be a ceiling. */
		public final List<int[]> deadPhiOperands;

		Ceiling(int[] dividendFactors, int divisorVN, List<int[]> deadPhiOperands) {
			this.dividendFactors = dividendFactors;
			this.divisorVN = divisorVN;
			this.deadPhiOperands = deadPhiOperands;
		}
	}

	/** The ceiling that the definition of {@code vn} (a phi, add or mul) computes, or null. */
	public Ceiling ceiling(int vn) {
		return ceilings.get(vn);
	}

	/**
	 * Recognizes the ways integer code computes {@code ceil(N / D)} from a rounded-down quotient,
	 * each exact for every input it admits:
	 * <ul>
	 * <li>branch: {@code q = N / D; if (N % D != 0) q = q + 1};
	 * <li>indicator: {@code N / D + [N % D != 0]}, with the indicator written as a comparison, a
	 *     {@code c ? 1 : 0} select, or a call returning one ({@code SafeCast.toUint(bool)});
	 * <li>predecrement: {@code (N - 1) / D + 1} where N != 0 is established by a dominating guard,
	 *     or masked as {@code [N != 0] * ((N - 1) / D + 1)}.
	 * </ul>
	 * The quotient is a Down division or a call modelled as one (see {@link Calls}).
	 */
	private void recognizeCeilings(IR ir) {
		SSACFG cfg = ir.getControlFlowGraph();
		Dominators<ISSABasicBlock> dom = Dominators.make(cfg, cfg.entry());
		Literals literals = new Literals(ir, dug.du(), CALL_DEPTH);
		for (ISSABasicBlock bb : cfg) {
			Iterator<SSAPhiInstruction> phis = bb.iteratePhis();
			while (phis.hasNext()) {
				SSAPhiInstruction phi = phis.next();
				Ceiling c = branchCeiling(phi, bb, ir, cfg, dom, literals);
				if (c != null) {
					ceilings.put(phi.getDef(), c);
				}
			}
		}
		ir.iterateNormalInstructions().forEachRemaining(inst -> {
			if (inst instanceof SSABinaryOpInstruction) {
				SSABinaryOpInstruction b = (SSABinaryOpInstruction) inst;
				Ceiling c = null;
				if (b.getOperator() == IBinaryOpInstruction.Operator.ADD) {
					c = indicatorCeiling(b, literals);
					if (c == null) {
						c = guardedPredecrementCeiling(b, ir, cfg, dom, literals);
					}
				} else if (b.getOperator() == IBinaryOpInstruction.Operator.MUL) {
					c = maskedPredecrementCeiling(b, literals);
				}
				if (c != null) {
					ceilings.put(b.getDef(), c);
				}
			}
		});
	}

	/** Branch form: phi(Q, Q + 1) whose Q + 1 arm is taken exactly when {@code N % D != 0}. */
	private Ceiling branchCeiling(SSAPhiInstruction phi, ISSABasicBlock merge, IR ir, SSACFG cfg,
			Dominators<ISSABasicBlock> dom, Literals literals) {
		if (phi.getNumberOfUses() != 2) {
			return null;
		}
		ISSABasicBlock gb = dom.getIdom(merge);
		SSAConditionalBranchInstruction cond = gb == null ? null : conditionalOf(gb, ir);
		BranchTest test = cond == null ? null : branchTest(cond, ir.getSymbolTable());
		if (test == null) {
			return null;
		}
		int[] arms = trueFalseArms(phi, merge, gb, cfg, test.takenMeansTrue, dom);
		Literal guard = literals.of(test.valueVN);
		if (arms == null) {
			return null;
		}
		// the arm taken when the remainder is nonzero, and the arm taken when it is zero
		int nonzeroArm = guard.positive ? arms[0] : arms[1];
		int zeroArm = guard.positive ? arms[1] : arms[0];
		Quotient q = quotient(zeroArm);
		if (q != null && isPlusOne(nonzeroArm, zeroArm) && isRemainderOf(guard.base, q)) {
			return new Ceiling(q.factors, q.divisor, guard.dead);
		}
		return null;
	}

	/** Indicator form: {@code Q + I} with Q = N / D and I = [N % D != 0], in either order. */
	private Ceiling indicatorCeiling(SSABinaryOpInstruction add, Literals literals) {
		for (int i = 0; i < 2; i++) {
			Quotient q = quotient(add.getUse(i));
			if (q == null) {
				continue;
			}
			Literal ind = literals.of(add.getUse(1 - i));
			if (ind.indicator && ind.positive && isRemainderOf(ind.base, q)) {
				return new Ceiling(q.factors, q.divisor, ind.dead);
			}
		}
		return null;
	}

	/** Predecrement form, where a dominating guard establishes N != 0. */
	private Ceiling guardedPredecrementCeiling(SSABinaryOpInstruction add, IR ir, SSACFG cfg,
			Dominators<ISSABasicBlock> dom, Literals literals) {
		Predecrement p = predecrement(add.getDef());
		if (p != null && nonzeroAt(p.dividendVN, cfg.getBlockForInstruction(add.iIndex()), ir, cfg, dom, literals)) {
			return new Ceiling(p.quotient.factors, p.quotient.divisor, Collections.emptyList());
		}
		return null;
	}

	/** Masked predecrement form: {@code [N != 0] * ((N - 1) / D + 1)}, which is 0 = ceil(0 / D) at N = 0. */
	private Ceiling maskedPredecrementCeiling(SSABinaryOpInstruction mul, Literals literals) {
		for (int i = 0; i < 2; i++) {
			Predecrement p = predecrement(mul.getUse(i));
			if (p == null) {
				continue;
			}
			Literal mask = literals.of(mul.getUse(1 - i));
			if (mask.indicator && mask.positive && sameExpr(mask.base, p.dividendVN, dug.du())) {
				return new Ceiling(p.quotient.factors, p.quotient.divisor, mask.dead);
			}
		}
		return null;
	}

	/** {@code N / D} rounded down: N as a product of factors, and D. */
	private static final class Quotient {
		final int[] factors;
		final int divisor;

		Quotient(int[] factors, int divisor) {
			this.factors = factors;
			this.divisor = divisor;
		}
	}

	/** The quotient {@code vn} computes: a Down division, or a call modelled as floor(a_1*...*a_k / d). */
	private Quotient quotient(int vn) {
		SSAInstruction def = dug.du().getDef(vn);
		if (def instanceof SSABinaryOpInstruction) {
			SSABinaryOpInstruction div = (SSABinaryOpInstruction) def;
			if (div.getOperator() == IBinaryOpInstruction.Operator.DIV && divDirection(div) == Direction.Down) {
				return new Quotient(factors(div.getUse(0)), div.getUse(1));
			}
		} else if (def instanceof SSAAbstractInvokeInstruction && calls.isFloorQuotient((SSAAbstractInvokeInstruction) def)) {
			// uses are [callee, a_1, ..., a_k, d]
			int n = def.getNumberOfUses();
			if (n >= 3) {
				List<Integer> f = new ArrayList<>();
				for (int i = 1; i < n - 1; i++) {
					for (int x : factors(def.getUse(i))) {
						f.add(x);
					}
				}
				return new Quotient(f.stream().mapToInt(Integer::intValue).toArray(), def.getUse(n - 1));
			}
		}
		return null;
	}

	/** {@code (N - 1) / D + 1}: N and the quotient of N by D. */
	private static final class Predecrement {
		final int dividendVN;
		final Quotient quotient;

		Predecrement(int dividendVN, Quotient quotient) {
			this.dividendVN = dividendVN;
			this.quotient = quotient;
		}
	}

	private Predecrement predecrement(int vn) {
		DefUse du = dug.du();
		SymbolTable st = dug.ir().getSymbolTable();
		SSAInstruction def = du.getDef(vn);
		if (!(def instanceof SSABinaryOpInstruction)
				|| ((SSABinaryOpInstruction) def).getOperator() != IBinaryOpInstruction.Operator.ADD) {
			return null;
		}
		for (int i = 0; i < 2; i++) {
			if (!isOne(def.getUse(1 - i), st)) {
				continue;
			}
			SSAInstruction d = du.getDef(def.getUse(i));
			if (d instanceof SSABinaryOpInstruction
					&& ((SSABinaryOpInstruction) d).getOperator() == IBinaryOpInstruction.Operator.DIV
					&& divDirection((SSABinaryOpInstruction) d) == Direction.Down) {
				SSAInstruction s = du.getDef(d.getUse(0));
				if (s instanceof SSABinaryOpInstruction
						&& ((SSABinaryOpInstruction) s).getOperator() == IBinaryOpInstruction.Operator.SUB
						&& isOne(s.getUse(1), st)) {
					int n = s.getUse(0);
					return new Predecrement(n, new Quotient(factors(n), d.getUse(1)));
				}
			}
		}
		return null;
	}

	/** True if {@code add} is {@code base + 1}. */
	private boolean isPlusOne(int add, int base) {
		SSAInstruction def = dug.du().getDef(add);
		SymbolTable st = dug.ir().getSymbolTable();
		return def instanceof SSABinaryOpInstruction
				&& ((SSABinaryOpInstruction) def).getOperator() == IBinaryOpInstruction.Operator.ADD
				&& ((def.getUse(0) == base && isOne(def.getUse(1), st)) || (def.getUse(1) == base && isOne(def.getUse(0), st)));
	}

	/** True if {@code vn} is {@code N % D} for the quotient's N and D. */
	private boolean isRemainderOf(int vn, Quotient q) {
		SSAInstruction def = dug.du().getDef(vn);
		if (def instanceof SSABinaryOpInstruction
				&& ((SSABinaryOpInstruction) def).getOperator() == IBinaryOpInstruction.Operator.REM) {
			return sameProduct(factors(def.getUse(0)), q.factors) && sameExpr(def.getUse(1), q.divisor, dug.du());
		}
		return false;
	}

	/** The factors of a product (a value that is not a multiplication is its only factor). */
	private int[] factors(int vn) {
		SSAInstruction def = dug.du().getDef(vn);
		if (def instanceof SSABinaryOpInstruction
				&& ((SSABinaryOpInstruction) def).getOperator() == IBinaryOpInstruction.Operator.MUL) {
			return IntStream.concat(IntStream.of(factors(def.getUse(0))), IntStream.of(factors(def.getUse(1)))).toArray();
		}
		return new int[] { vn };
	}

	/** The two factor lists multiply the same expressions, in any order. */
	private boolean sameProduct(int[] f1, int[] f2) {
		if (f1.length != f2.length) {
			return false;
		}
		boolean[] used = new boolean[f2.length];
		outer: for (int a : f1) {
			for (int j = 0; j < f2.length; j++) {
				if (!used[j] && sameExpr(a, f2[j], dug.du())) {
					used[j] = true;
					continue outer;
				}
			}
			return false;
		}
		return true;
	}

	/**
	 * True if {@code vn != 0} holds at {@code at}: a nonzero constant, a product of nonzero factors
	 * (overflow is not modelled, as throughout the analysis), or a value a dominating branch has
	 * tested against zero.
	 */
	private boolean nonzeroAt(int vn, ISSABasicBlock at, IR ir, SSACFG cfg, Dominators<ISSABasicBlock> dom, Literals literals) {
		SymbolTable st = ir.getSymbolTable();
		if (st.isConstant(vn)) {
			return truthValue(st.getConstantValue(vn));
		}
		SSAInstruction def = dug.du().getDef(vn);
		if (def instanceof SSABinaryOpInstruction
				&& ((SSABinaryOpInstruction) def).getOperator() == IBinaryOpInstruction.Operator.MUL) {
			return nonzeroAt(def.getUse(0), at, ir, cfg, dom, literals) && nonzeroAt(def.getUse(1), at, ir, cfg, dom, literals);
		}
		// A block entered only through one edge of a conditional knows that edge's outcome.
		for (ISSABasicBlock b = at; b != null; b = dom.getIdom(b)) {
			if (cfg.getPredNodeCount(b) != 1) {
				continue;
			}
			ISSABasicBlock g = cfg.getPredNodes(b).next();
			SSAConditionalBranchInstruction cond = conditionalOf(g, ir);
			BranchTest test = cond == null ? null : branchTest(cond, st);
			ISSABasicBlock taken = test == null ? null : Util.getTakenSuccessor(cfg, g);
			if (test == null || taken.equals(Util.getNotTakenSuccessor(cfg, g))) {
				continue;
			}
			boolean valueNonzero = b.equals(taken) == test.takenMeansTrue;
			Literal l = literals.of(test.valueVN);
			if (l.dead.isEmpty() && sameExpr(l.base, vn, dug.du()) && valueNonzero == l.positive) {
				return true;
			}
		}
		return false;
	}

	/** How deep the indicator analysis follows calls. */
	private static final int CALL_DEPTH = 3;

	/**
	 * When a value is nonzero: {@code v != 0} holds exactly when {@code base != 0} does
	 * ({@code positive}) or when {@code base == 0} does (not {@code positive}), on the paths where
	 * the {@code dead} phi operands are not taken. {@code indicator}: v is always 0 or 1.
	 */
	private static final class Literal {
		final int base;
		final boolean positive;
		final boolean indicator;
		final List<int[]> dead;

		Literal(int base, boolean positive, boolean indicator, List<int[]> dead) {
			this.base = base;
			this.positive = positive;
			this.indicator = indicator;
			this.dead = dead;
		}

		/** The literal of {@code (v != 0)} as a 0/1 value. */
		Literal asIndicator() {
			return new Literal(base, positive, true, dead);
		}

		/** The literal of {@code (v == 0)} as a 0/1 value. */
		Literal negatedIndicator() {
			return new Literal(base, !positive, true, dead);
		}
	}

	/**
	 * Computes {@link Literal}s in one method, looking through comparisons with zero, boolean
	 * negation, {@code c ? 1 : 0} selects, short-circuit phis and calls whose result is an
	 * indicator of an argument.
	 */
	private final class Literals {
		private final IR ir;
		private final DefUse du;
		private final SymbolTable st;
		private final int depth;
		private final Set<Integer> visiting = HashSetFactory.make();
		private Dominators<ISSABasicBlock> dom;

		Literals(IR ir, DefUse du, int depth) {
			this.ir = ir;
			this.du = du;
			this.st = ir.getSymbolTable();
			this.depth = depth;
		}

		Literal of(int vn) {
			if (!visiting.add(vn)) {
				return new Literal(vn, true, false, Collections.emptyList()); // a cycle through loop phis
			}
			try {
				return compute(vn);
			} finally {
				visiting.remove(vn);
			}
		}

		private Literal compute(int vn) {
			Literal l = null;
			SSAInstruction def = du.getDef(vn);
			if (def instanceof SSAUnaryOpInstruction
					&& ((SSAUnaryOpInstruction) def).getOpcode() == IUnaryOpInstruction.Operator.NEG) {
				l = of(def.getUse(0)).negatedIndicator(); // boolean `!x`
			} else if (def instanceof SSABinaryOpInstruction) {
				l = comparisonWithZero((SSABinaryOpInstruction) def);
			} else if (def instanceof SSAPhiInstruction) {
				l = select((SSAPhiInstruction) def);
				if (l == null) {
					l = shortCircuit((SSAPhiInstruction) def);
				}
			} else if (def instanceof SSAAbstractInvokeInstruction && depth > 0) {
				l = call((SSAAbstractInvokeInstruction) def);
			}
			return l != null ? l : new Literal(vn, true, false, Collections.emptyList());
		}

		/** {@code x == 0}, {@code x != 0}, and for non-negative x, {@code x > 0} and {@code x <= 0}. */
		private Literal comparisonWithZero(SSABinaryOpInstruction b) {
			boolean zeroRight = isZero(b.getUse(1)), zeroLeft = isZero(b.getUse(0));
			if (zeroRight == zeroLeft) {
				return null;
			}
			int x = zeroRight ? b.getUse(0) : b.getUse(1);
			IBinaryOpInstruction.IOperator op = b.getOperator();
			if (op == CAstBinaryOp.EQ) {
				return of(x).negatedIndicator();
			}
			if (op == CAstBinaryOp.NE) {
				return of(x).asIndicator();
			}
			if (nonNegative(x)) {
				if ((zeroRight && op == CAstBinaryOp.GT) || (zeroLeft && op == CAstBinaryOp.LT)) {
					return of(x).asIndicator(); // x > 0 <=> x != 0
				}
				if ((zeroRight && op == CAstBinaryOp.LE) || (zeroLeft && op == CAstBinaryOp.GE)) {
					return of(x).negatedIndicator(); // x <= 0 <=> x == 0
				}
			}
			return null;
		}

		/** {@code c ? k : 0} (or {@code c ? 0 : k}) with k a nonzero constant: nonzero exactly when c is (is not). */
		private Literal select(SSAPhiInstruction phi) {
			if (phi.getNumberOfUses() != 2 || !st.isConstant(phi.getUse(0)) || !st.isConstant(phi.getUse(1))) {
				return null;
			}
			SSACFG cfg = ir.getControlFlowGraph();
			if (dom == null) {
				dom = Dominators.make(cfg, cfg.entry());
			}
			ISSABasicBlock merge = phiBlock(phi, cfg);
			ISSABasicBlock gb = merge == null ? null : dom.getIdom(merge);
			SSAConditionalBranchInstruction cond = gb == null ? null : conditionalOf(gb, ir);
			BranchTest test = cond == null ? null : branchTest(cond, st);
			int[] arms = test == null ? null : trueFalseArms(phi, merge, gb, cfg, test.takenMeansTrue, dom);
			if (arms == null) {
				return null;
			}
			boolean whenTrue = truthValue(st.getConstantValue(arms[0]));
			boolean whenFalse = truthValue(st.getConstantValue(arms[1]));
			if (whenTrue == whenFalse) {
				return null;
			}
			Literal c = of(test.valueVN);
			boolean zeroOne = isZeroOrOne(arms[0]) && isZeroOrOne(arms[1]);
			return new Literal(c.base, whenTrue ? c.positive : !c.positive, zeroOne, c.dead);
		}

		/**
		 * A short-circuit merge such as {@code a && b}: phi(b, false). On the paths where every
		 * constant-zero operand is infeasible it is the remaining operand; those operands become
		 * side conditions for the calling context to discharge.
		 */
		private Literal shortCircuit(SSAPhiInstruction phi) {
			int live = -1;
			List<int[]> dead = new ArrayList<>();
			for (int i = 0; i < phi.getNumberOfUses(); i++) {
				int u = phi.getUse(i);
				if (isZero(u)) {
					dead.add(new int[] { phi.getDef(), i });
				} else if (live == -1) {
					live = u;
				} else {
					return null;
				}
			}
			if (live == -1 || dead.isEmpty()) {
				return null;
			}
			Literal l = of(live);
			List<int[]> all = new ArrayList<>(l.dead);
			all.addAll(dead);
			return new Literal(l.base, l.positive, l.indicator, all);
		}

		/** A call whose every target returns an indicator of one parameter (e.g. {@code SafeCast.toUint(bool)}). */
		private Literal call(SSAAbstractInvokeInstruction call) {
			Collection<IR> targets = calls.targets(call);
			Literal agreed = null;
			for (IR callee : targets) {
				Literals inner = new Literals(callee, new DefUse(callee), depth - 1);
				for (SSAInstruction inst : callee.getInstructions()) {
					if (!(inst instanceof SSAReturnInstruction) || inst.getNumberOfUses() == 0) {
						continue;
					}
					Literal r = inner.of(inst.getUse(0));
					boolean param = r.base > 1 && r.base <= callee.getNumberOfParameters();
					if (!r.dead.isEmpty() || !param || (agreed != null && (agreed.base != r.base
							|| agreed.positive != r.positive || agreed.indicator != r.indicator))) {
						return null;
					}
					agreed = r;
				}
			}
			if (agreed == null || agreed.base - 1 >= call.getNumberOfUses()) {
				return null;
			}
			// callee parameter v_p is the call's use p - 1 (use 0 is the callee itself)
			Literal arg = of(call.getUse(agreed.base - 1));
			return new Literal(arg.base, arg.positive == agreed.positive, agreed.indicator, arg.dead);
		}

		private boolean isZero(int vn) {
			return st.isConstant(vn) && !truthValue(st.getConstantValue(vn));
		}

		private boolean isZeroOrOne(int vn) {
			Object c = st.getConstantValue(vn);
			return c instanceof Boolean || (c instanceof Number && (((Number) c).longValue() == 0 || ((Number) c).longValue() == 1));
		}

		/** Remainders (of the unsigned operands the analysis assumes), 0/1 values, and unsigned parameters. */
		private boolean nonNegative(int vn) {
			SSAInstruction def = du.getDef(vn);
			if (def instanceof SSABinaryOpInstruction
					&& ((SSABinaryOpInstruction) def).getOperator() == IBinaryOpInstruction.Operator.REM) {
				return true;
			}
			if (vn > 1 && vn <= ir.getNumberOfParameters()) {
				String type = ir.getMethod().getParameterType(vn - 1).getName().toString();
				return type.startsWith("Puint") || type.equals("Pbool");
			}
			return of(vn).indicator;
		}
	}

	private static ISSABasicBlock phiBlock(SSAPhiInstruction phi, SSACFG cfg) {
		for (ISSABasicBlock bb : cfg) {
			Iterator<SSAPhiInstruction> phis = bb.iteratePhis();
			while (phis.hasNext()) {
				if (phis.next() == phi) {
					return bb;
				}
			}
		}
		return null;
	}

	/** Maps the two phi operands to the true / false side of the branch in gb; null if unclear. */
	private int[] trueFalseArms(SSAPhiInstruction phi, ISSABasicBlock merge, ISSABasicBlock gb,
			SSACFG cfg, boolean takenMeansTrue, Dominators<ISSABasicBlock> dom) {
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
			if (takenSide == takenMeansTrue) {
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
		int[] arms = trueFalseArms(phi, merge, gb, cfg, cmp.takenMeansTrue, dom);
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
		BranchTest test = branchTest(cond, st);
		if (test == null) {
			return null;
		}
		int boolVN = test.valueVN;
		boolean takenMeansTrue = test.takenMeansTrue;

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

	/** A conditional branch that tests a value against a constant. */
	private static final class BranchTest {
		final int valueVN;
		final boolean takenMeansTrue; // does the taken edge mean the value is true (nonzero)?

		BranchTest(int valueVN, boolean takenMeansTrue) {
			this.valueVN = valueVN;
			this.takenMeansTrue = takenMeansTrue;
		}
	}

	/** The value a branch-on-boolean lowering tests, and which edge means it is true; null for other shapes. */
	private static BranchTest branchTest(SSAConditionalBranchInstruction cond, SymbolTable st) {
		int u0 = cond.getUse(0), u1 = cond.getUse(1);
		int valueVN, constVN;
		if (st.isConstant(u1) && !st.isConstant(u0)) {
			valueVN = u0;
			constVN = u1;
		} else if (st.isConstant(u0) && !st.isConstant(u1)) {
			valueVN = u1;
			constVN = u0;
		} else {
			return null;
		}
		boolean constTrue = truthValue(st.getConstantValue(constVN));
		if (cond.getOperator() == IConditionalBranchInstruction.Operator.EQ) {
			return new BranchTest(valueVN, constTrue);
		} else if (cond.getOperator() == IConditionalBranchInstruction.Operator.NE) {
			return new BranchTest(valueVN, !constTrue);
		}
		return null;
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
				if (guardedMerges.containsKey(def) || loopInductions.containsKey(def)) {
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

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
package com.certora.wala.analysis.rounding.eval;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.certora.wala.analysis.rounding.Direction;
import com.certora.wala.analysis.rounding.RoundingGraph;

import com.ibm.wala.cfg.Util;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.shrike.shrikeBT.IConditionalBranchInstruction;
import com.ibm.wala.shrike.shrikeBT.IShiftInstruction;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.util.graph.dominators.Dominators;

/**
 * Concrete interpretation of Q for the differential harness: the same fold over
 * {@link RoundingGraph} instantiated at two value domains. Over the integers every
 * {@link RoundingGraph.Div} rounds per its {@code rounds()}; over the rationals it divides
 * exactly, which is the transduced (intended) semantics. The two runs of one sample share a
 * {@link LeafOracle}, so unmodelled values are identical on both sides - the analysis's
 * boundary assumption, made operational.
 *
 * <p>A sample that leaves the modelled domain (division by zero, a negative integer value,
 * an unevaluable node kind) throws {@link Discard}; the harness drops the sample and counts
 * the reason. Q encodes {@code x << k} as {@code Mul(x, k)} and {@code x >> k} as
 * {@code Div(x, k)} - direction-faithful but concretely wrong - so this evaluator consults
 * the defining IR instruction at those nodes and interprets shift-origins as {@code 2^k}.
 */
public final class QEval {

	/** The sample cannot be evaluated in the modelled domain; drop it and count the reason. */
	public static final class Discard extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public Discard(String reason) {
			super(reason, null, false, false);
		}
	}

	/** The value domain: one implementation over BigInteger, one over {@link Rational}. */
	public interface Dom<V> {
		V fromBig(BigInteger c);

		V add(V a, V b);

		V sub(V a, V b);

		V mul(V a, V b);

		V neg(V a);

		/** {@code round(num / den)} per {@code rounds}; exact in the rational domain. */
		V div(V num, V den, Direction rounds);

		/** Multiply by 2^k (a left shift's meaning). */
		V shiftLeft(V a, BigInteger k);

		/** Divide by 2^k, rounding per {@code rounds} (a right shift's meaning). */
		V shiftRight(V a, BigInteger k, Direction rounds);

		int cmp(V a, V b);
	}

	/** The integer run: what the deployed code computes (non-negative, no overflow modelled). */
	public static final class IntDom implements Dom<BigInteger> {
		@Override
		public BigInteger fromBig(BigInteger c) {
			return c;
		}

		@Override
		public BigInteger add(BigInteger a, BigInteger b) {
			return a.add(b);
		}

		@Override
		public BigInteger sub(BigInteger a, BigInteger b) {
			BigInteger r = a.subtract(b);
			if (r.signum() < 0) {
				throw new Discard("negative");
			}
			return r;
		}

		@Override
		public BigInteger mul(BigInteger a, BigInteger b) {
			return a.multiply(b);
		}

		@Override
		public BigInteger neg(BigInteger a) {
			if (a.signum() != 0) {
				throw new Discard("negative");
			}
			return a;
		}

		@Override
		public BigInteger div(BigInteger num, BigInteger den, Direction rounds) {
			if (den.signum() == 0) {
				throw new Discard("div0");
			}
			if (rounds == Direction.Up) {
				return num.add(den).subtract(BigInteger.ONE).divide(den);
			}
			return num.divide(den); // floor for the non-negative domain; exact for Neither (den = 1)
		}

		@Override
		public BigInteger shiftLeft(BigInteger a, BigInteger k) {
			return a.shiftLeft(k.intValueExact());
		}

		@Override
		public BigInteger shiftRight(BigInteger a, BigInteger k, Direction rounds) {
			return a.shiftRight(k.intValueExact());
		}

		@Override
		public int cmp(BigInteger a, BigInteger b) {
			return a.compareTo(b);
		}
	}

	/** The real run: the same syntax with every division exact. */
	public static final class RatDom implements Dom<Rational> {
		@Override
		public Rational fromBig(BigInteger c) {
			return Rational.of(c);
		}

		@Override
		public Rational add(Rational a, Rational b) {
			return a.plus(b);
		}

		@Override
		public Rational sub(Rational a, Rational b) {
			return a.minus(b);
		}

		@Override
		public Rational mul(Rational a, Rational b) {
			return a.times(b);
		}

		@Override
		public Rational neg(Rational a) {
			return a.negate();
		}

		@Override
		public Rational div(Rational num, Rational den, Direction rounds) {
			if (den.num.signum() == 0) {
				throw new Discard("div0");
			}
			return num.dividedBy(den); // exact: rounds is the integer run's business
		}

		@Override
		public Rational shiftLeft(Rational a, BigInteger k) {
			return a.times(Rational.of(BigInteger.TWO.pow(k.intValueExact())));
		}

		@Override
		public Rational shiftRight(Rational a, BigInteger k, Direction rounds) {
			return a.dividedBy(Rational.of(BigInteger.TWO.pow(k.intValueExact())));
		}

		@Override
		public int cmp(Rational a, Rational b) {
			return a.compareTo(b);
		}
	}

	/**
	 * One shared draw per sample: the value of each unmodelled leaf (Opaque, Load,
	 * unresolvable call), keyed by (node, vn) so both domains and both strictness passes see
	 * the same values.
	 */
	public static final class LeafOracle {
		private final Map<CGNode, Map<Integer, BigInteger>> drawn = new HashMap<>();
		private final Function<Void, BigInteger> draw;

		public LeafOracle(java.util.function.Supplier<BigInteger> draw) {
			this.draw = x -> draw.get();
		}

		BigInteger value(CGNode n, int vn) {
			return drawn.computeIfAbsent(n, x -> new HashMap<>()).computeIfAbsent(vn, x -> draw.apply(null));
		}
	}

	/** How a GuardedMerge resolves {@code g == bound}: the recognizer does not keep strictness. */
	public enum Strictness {
		THEN_ON_EQUAL, ELSE_ON_EQUAL
	}

	private final CallGraph cg;
	private final Function<CGNode, RoundingGraph> graphs;
	private final Map<CGNode, Code> codeCache = new HashMap<>();

	public QEval(CallGraph cg, Function<CGNode, RoundingGraph> graphs) {
		this.cg = cg;
		this.graphs = graphs;
	}

	/**
	 * One node's IR with the def-use and dominator information built from that same IR
	 * object. {@code n.getIR()} and {@code n.getDU()} cannot be mixed: WALA caches the IR
	 * per method but the def-use per (method, context), and a cache wipe (soft-reference
	 * clearing, {@code ReferenceCleanser}) rebuilds the IR, so a later {@code getDU()} can
	 * hold another IR's instructions and identity lookups of its phis fail - at a
	 * memory-dependent, run-to-run varying point. Q refers only to value numbers and call
	 * sites, which every rebuild of the IR preserves.
	 */
	private record Code(IR ir, DefUse du, Dominators<ISSABasicBlock> dom) {
	}

	private Code code(CGNode n) {
		return codeCache.computeIfAbsent(n, x -> {
			IR ir = x.getIR();
			return new Code(ir, new DefUse(ir),
					Dominators.make(ir.getControlFlowGraph(), ir.getControlFlowGraph().entry()));
		});
	}

	private static SSAConditionalBranchInstruction conditionalOf(ISSABasicBlock bb, IR ir) {
		int last = bb.getLastInstructionIndex();
		if (last < 0) {
			return null;
		}
		SSAInstruction inst = ir.getInstructions()[last];
		return inst instanceof SSAConditionalBranchInstruction c ? c : null;
	}

	/** Set when any GuardedMerge in the sample compared equal values: strictness matters. */
	public boolean sawBoundaryEquality;

	/** Evaluate one method on one argument vector in one domain. */
	public <V> V run(CGNode n, List<V> args, Dom<V> dom, LeafOracle leaves, Strictness s) {
		sawBoundaryEquality = false;
		return new Frame<>(dom, leaves, s, new HashSet<>()).evalMethod(n, args);
	}

	private final class Frame<V> {
		private final Dom<V> dom;
		private final LeafOracle leaves;
		private final Strictness strictness;
		private final Set<CGNode> active;

		Frame(Dom<V> dom, LeafOracle leaves, Strictness s, Set<CGNode> active) {
			this.dom = dom;
			this.leaves = leaves;
			this.strictness = s;
			this.active = active;
		}

		V evalMethod(CGNode n, List<V> args) {
			return evalMethod(n, args, null);
		}

		/** The method's returned value, or with a {@code component}, that component of its returned tuple. */
		V evalMethod(CGNode n, List<V> args, FieldReference component) {
			if (!active.add(n)) {
				throw new Discard("recursion");
			}
			try {
				RoundingGraph q = graphs.apply(n);
				if (q == null) {
					throw new Discard("noQ");
				}
				int[] rets = q.returnVns();
				if (rets.length != 1) {
					throw new Discard("returns" + rets.length);
				}
				if (component == null) {
					return eval(n, q, new HashMap<>(), args, rets[0]);
				}
				for (Iterator<SSAInstruction> uses = code(n).du().getUses(rets[0]); uses.hasNext();) {
					if (uses.next() instanceof SSAPutInstruction put && put.getRef() == rets[0]
							&& put.getDeclaredField().getName().equals(component.getName())) {
						return eval(n, q, new HashMap<>(), args, put.getVal());
					}
				}
				throw new Discard("tupleComponent");
			} finally {
				active.remove(n);
			}
		}

		V eval(CGNode n, RoundingGraph q, Map<Integer, V> memo, List<V> args, int rawVn) {
			RoundingGraph.Node node = q.node(rawVn);
			SymbolTable st = code(n).ir().getSymbolTable();
			if (node == null) {
				if (st.isConstant(rawVn)) {
					return dom.fromBig(constant(st, rawVn));
				}
				throw new Discard("noNode");
			}
			int vn = node.vn();
			V memoed = memo.get(vn);
			if (memoed != null) {
				return memoed;
			}
			V r = switch (node) {
			case RoundingGraph.Const c -> dom.fromBig(constant(st, c.vn()));
			case RoundingGraph.Param p -> {
				if (p.index() >= args.size()) {
					throw new Discard("paramIndex");
				}
				yield args.get(p.index());
			}
			case RoundingGraph.Opaque o -> dom.fromBig(leaves.value(n, vn));
			case RoundingGraph.Load l -> dom.fromBig(leaves.value(n, vn));
			case RoundingGraph.Add a -> dom.add(eval(n, q, memo, args, a.left()), eval(n, q, memo, args, a.right()));
			case RoundingGraph.Sub sNode ->
				dom.sub(eval(n, q, memo, args, sNode.left()), eval(n, q, memo, args, sNode.right()));
			case RoundingGraph.Neg g -> dom.neg(eval(n, q, memo, args, g.operand()));
			case RoundingGraph.Assign a -> eval(n, q, memo, args, a.operand());
			case RoundingGraph.Mul m -> {
				V l = eval(n, q, memo, args, m.left());
				SSAInstruction def = code(n).du().getDef(vn);
				if (isShift(def, IShiftInstruction.Operator.SHL)) {
					yield dom.shiftLeft(l, exponent(n, q, memo, args, m.right()));
				}
				yield dom.mul(l, eval(n, q, memo, args, m.right()));
			}
			case RoundingGraph.Div d -> {
				V num = null;
				for (int f : d.numerator()) {
					V v = eval(n, q, memo, args, f);
					num = num == null ? v : dom.mul(num, v);
				}
				SSAInstruction def = code(n).du().getDef(vn);
				if (!d.idiom() && (isShift(def, IShiftInstruction.Operator.SHR)
						|| isShift(def, IShiftInstruction.Operator.USHR))) {
					yield dom.shiftRight(num, exponent(n, q, memo, args, d.divisor()), d.rounds());
				}
				yield dom.div(num, eval(n, q, memo, args, d.divisor()), d.rounds());
			}
			case RoundingGraph.GuardedMerge g -> {
				V gv = eval(n, q, memo, args, g.guard());
				V bv = eval(n, q, memo, args, g.bound());
				int c = dom.cmp(gv, bv);
				if (c == 0) {
					sawBoundaryEquality = true;
				}
				boolean then = c < 0 || (c == 0 && strictness == Strictness.THEN_ON_EQUAL);
				yield eval(n, q, memo, args, then ? g.thenArm() : g.elseArm());
			}
			case RoundingGraph.Call c -> {
				var targets = cg.getPossibleTargets(n, c.site().getCallSite());
				if (targets.size() != 1) {
					yield dom.fromBig(leaves.value(n, vn));
				}
				CGNode callee = targets.iterator().next();
				if (callee.getIR() == null || graphs.apply(callee) == null) {
					yield dom.fromBig(leaves.value(n, vn));
				}
				int uses = c.site().getNumberOfUses();
				List<V> callArgs = new java.util.ArrayList<>(uses);
				for (int i = 0; i < uses; i++) {
					callArgs.add(eval(n, q, memo, args, c.site().getUse(i)));
				}
				yield evalMethod(callee, callArgs, c.component());
			}
			case RoundingGraph.Bitwise b -> throw new Discard("bitwise");
			case RoundingGraph.Merge m -> phiMerge(n, q, memo, args, vn);
			case RoundingGraph.LoopMerge l -> throw new Discard("loop");
			case RoundingGraph.FloorMerge f -> phiMerge(n, q, memo, args, vn);
			};
			memo.put(vn, r);
			return r;
		}

		/**
		 * Concrete arm selection for a two-arm merge: find the phi's block, evaluate its
		 * immediate dominator's conditional in this domain over Q values, and take the phi
		 * operand on the selected path. Merges whose shape this cannot resolve discard.
		 */
		private V phiMerge(CGNode n, RoundingGraph q, Map<Integer, V> memo, List<V> args, int vn) {
			Code code = code(n);
			if (!(code.du().getDef(vn) instanceof SSAPhiInstruction phi) || phi.getNumberOfUses() != 2) {
				throw new Discard("mergeShape");
			}
			SSACFG cfg = code.ir().getControlFlowGraph();
			ISSABasicBlock merge = blockOf(code, phi);
			Dominators<ISSABasicBlock> dom = code.dom();
			ISSABasicBlock gb = dom.getIdom(merge);
			SSAConditionalBranchInstruction cond = gb == null ? null : conditionalOf(gb, code.ir());
			if (cond == null) {
				throw new Discard("mergeNoCond");
			}
			boolean taken = branchTaken(n, q, memo, args, cond);
			ISSABasicBlock sel = taken ? Util.getTakenSuccessor(cfg, gb) : Util.getNotTakenSuccessor(cfg, gb);
			int idx = -1, j = 0;
			for (java.util.Iterator<ISSABasicBlock> ps = cfg.getPredNodes(merge); ps.hasNext(); j++) {
				ISSABasicBlock p = ps.next();
				boolean viaSel = p.equals(gb) ? sel.equals(merge) : (p.equals(sel) || dom.isDominatedBy(p, sel));
				if (viaSel) {
					if (idx >= 0) {
						throw new Discard("mergeAmbiguous");
					}
					idx = j;
				}
			}
			if (idx < 0) {
				throw new Discard("mergeNoPath");
			}
			return eval(n, q, memo, args, phi.getUse(idx));
		}

		/** The conditional's truth value in this domain, through comparison scaffolding. */
		private boolean branchTaken(CGNode n, RoundingGraph q, Map<Integer, V> memo, List<V> args,
				SSAConditionalBranchInstruction cond) {
			int c = dom.cmp(guardValue(n, q, memo, args, cond.getUse(0)),
					guardValue(n, q, memo, args, cond.getUse(1)));
			if (cond.getOperator() == IConditionalBranchInstruction.Operator.EQ) {
				return c == 0;
			}
			if (cond.getOperator() == IConditionalBranchInstruction.Operator.NE) {
				return c != 0;
			}
			throw new Discard("branchOp");
		}

		/**
		 * The value of a guard operand as this domain's 0/1 or number: a constant, a
		 * comparison lowering (possibly under boolean negations) evaluated to 0/1, or an
		 * ordinary Q value.
		 */
		private V guardValue(CGNode n, RoundingGraph q, Map<Integer, V> memo, List<V> args, int u) {
			SymbolTable st = code(n).ir().getSymbolTable();
			if (st.isConstant(u)) {
				return dom.fromBig(constant(st, u));
			}
			int neg = 0;
			SSAInstruction def = code(n).du().getDef(u);
			while (def instanceof com.ibm.wala.ssa.SSAUnaryOpInstruction un
					&& un.getOpcode() == com.ibm.wala.shrike.shrikeBT.IUnaryOpInstruction.Operator.NEG) {
				neg++;
				u = def.getUse(0);
				if (st.isConstant(u)) {
					break;
				}
				def = code(n).du().getDef(u);
			}
			boolean truth;
			if (!st.isConstant(u) && def instanceof SSABinaryOpInstruction b
					&& b.getOperator() instanceof com.ibm.wala.cast.ir.ssa.CAstBinaryOp cb && isComparison(cb)) {
				int c = dom.cmp(guardValue(n, q, memo, args, b.getUse(0)), guardValue(n, q, memo, args, b.getUse(1)));
				truth = compare(c, cb);
			} else {
				V v = st.isConstant(u) ? dom.fromBig(constant(st, u)) : eval(n, q, memo, args, u);
				if (neg == 0) {
					return v;
				}
				truth = dom.cmp(v, dom.fromBig(BigInteger.ZERO)) != 0;
			}
			if (neg % 2 == 1) {
				truth = !truth;
			}
			return dom.fromBig(truth ? BigInteger.ONE : BigInteger.ZERO);
		}

		private boolean compare(int c, com.ibm.wala.cast.ir.ssa.CAstBinaryOp op) {
			if (op == com.ibm.wala.cast.ir.ssa.CAstBinaryOp.EQ) return c == 0;
			if (op == com.ibm.wala.cast.ir.ssa.CAstBinaryOp.NE) return c != 0;
			if (op == com.ibm.wala.cast.ir.ssa.CAstBinaryOp.LT) return c < 0;
			if (op == com.ibm.wala.cast.ir.ssa.CAstBinaryOp.LE) return c <= 0;
			if (op == com.ibm.wala.cast.ir.ssa.CAstBinaryOp.GT) return c > 0;
			if (op == com.ibm.wala.cast.ir.ssa.CAstBinaryOp.GE) return c >= 0;
			throw new Discard("cmpOp");
		}

		private boolean isComparison(com.ibm.wala.cast.ir.ssa.CAstBinaryOp op) {
			return op == com.ibm.wala.cast.ir.ssa.CAstBinaryOp.EQ || op == com.ibm.wala.cast.ir.ssa.CAstBinaryOp.NE
					|| op == com.ibm.wala.cast.ir.ssa.CAstBinaryOp.LT || op == com.ibm.wala.cast.ir.ssa.CAstBinaryOp.LE
					|| op == com.ibm.wala.cast.ir.ssa.CAstBinaryOp.GT || op == com.ibm.wala.cast.ir.ssa.CAstBinaryOp.GE;
		}

		private ISSABasicBlock blockOf(Code code, SSAPhiInstruction phi) {
			for (ISSABasicBlock bb : code.ir().getControlFlowGraph()) {
				for (java.util.Iterator<SSAPhiInstruction> it = bb.iteratePhis(); it.hasNext();) {
					if (it.next() == phi) {
						return bb;
					}
				}
			}
			throw new Discard("phiBlock");
		}

		/** A shift amount must be a concrete small integer in both domains. */
		private BigInteger exponent(CGNode n, RoundingGraph q, Map<Integer, V> memo, List<V> args, int vn) {
			V v = eval(n, q, memo, args, vn);
			if (v instanceof BigInteger b) {
				return b;
			}
			if (v instanceof Rational r && r.isInteger()) {
				return r.num;
			}
			throw new Discard("fractionalShift");
		}
	}

	private static boolean isShift(SSAInstruction def, IShiftInstruction.Operator op) {
		return def instanceof SSABinaryOpInstruction b && b.getOperator() == op;
	}

	static BigInteger constant(SymbolTable st, int vn) {
		Object v = st.getConstantValue(vn);
		if (v instanceof BigInteger b) {
			return b;
		}
		if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
			return BigInteger.valueOf(((Number) v).longValue());
		}
		if (v instanceof String s) {
			try {
				return new BigInteger(s.trim());
			} catch (NumberFormatException e) {
				throw new Discard("constString");
			}
		}
		if (v instanceof java.math.BigDecimal bd) {
			try {
				return bd.toBigIntegerExact();
			} catch (ArithmeticException e) {
				throw new Discard("constFractional");
			}
		}
		if (v instanceof Double d) {
			if (d == Math.rint(d) && !d.isInfinite()) {
				return BigInteger.valueOf((long) (double) d);
			}
			throw new Discard("constDouble");
		}
		if (v instanceof Boolean b) {
			return b ? BigInteger.ONE : BigInteger.ZERO;
		}
		throw new Discard("const:" + (v == null ? "null" : v.getClass().getSimpleName()));
	}

}

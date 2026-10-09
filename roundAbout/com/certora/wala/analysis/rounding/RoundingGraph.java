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

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.util.graph.labeled.NumberedLabeledGraph;
import com.ibm.wala.util.graph.labeled.SlowSparseNumberedLabeledGraph;

/**
 * Phase 1's product for one method: an explicit graph Q of the operations the method performs on
 * amounts, connected by edges for the dataflow and control dependence a rounding direction can
 * travel along. Q never mentions a {@link Direction} computed by the analysis, so it is built once
 * per {@code CGNode} and solved once per calling direction context by Phase 2, which runs one
 * transfer function per node kind over exactly the operands listed here.
 *
 * <p>A recognized idiom is a single node: for {@code z = add(div(x, d), gt(mod(x, d), 0))} only
 * {@code z} maps to a {@link Div} that rounds Up over {@code x} and {@code d}; the inner division,
 * remainder and comparison are absorbed scaffolding, map to no node, and get no annotation. A
 * scaffolding value that also flows somewhere else keeps its own node, so nothing that is used
 * outside an idiom ever loses its direction.
 */
public class RoundingGraph {

	/** How an operand feeds a node: as a value it computes with, or as a guard that only selects. */
	public enum EdgeKind {
		VALUE, GUARD
	}

	/**
	 * One operation of Q. A node names the value number it defines ({@link #vn()}) and its
	 * operand value numbers; Phase 2 turns each kind into its transfer function. The interface is
	 * sealed so that Phase 2's dispatch is an exhaustive {@code switch}: a new node kind that is
	 * not handled there is a compile error, not a silently missing equation.
	 */
	public sealed interface Node
			permits Const, Param, Opaque, Add, Mul, Sub, Neg, Assign, Div, Bitwise, Merge, GuardedMerge, LoopMerge, FloorMerge, Load, Call {
		/** The value number this node defines. */
		int vn();
	}

	/** A compile-time constant: exact. */
	public record Const(int vn) implements Node {
	}

	/** Parameter {@code index} (0-based): its direction comes from the calling context. */
	public record Param(int vn, int index) implements Node {
	}

	/**
	 * A value the analysis does not model as an amount: a comparison or remainder, a value that
	 * only names a location, a load, or an instruction this context cannot execute. A
	 * {@code forced} node is pinned exact by an equation; an unforced one merely keeps the
	 * solver's initial state, exactly as an instruction without an equation did before.
	 */
	public record Opaque(int vn, String why, boolean forced) implements Node {
	}

	public record Add(int vn, int left, int right) implements Node {
	}

	/** A multiplication, or a left shift ({@code x << k} is {@code x * 2^k}). */
	public record Mul(int vn, int left, int right) implements Node {
	}

	public record Sub(int vn, int left, int right) implements Node {
	}

	public record Neg(int vn, int operand) implements Node {
	}

	/** A copy: pi, checkcast, or a unary operator that does not negate. */
	public record Assign(int vn, int operand) implements Node {
	}

	/**
	 * {@code round(N1 * ... * Nk / D)}, where {@code rounds} is the direction of the rounding
	 * operation itself: Down for a plain division or right shift, Up for a recognized ceiling
	 * idiom or the {@code (a+b-1)/b} bias, Neither for a division by one, Inconsistent for a
	 * ceiling idiom whose guard (a rounding mode) is not a constant in this context, which is the
	 * floor in some runs and the ceiling in others. The paper's DivDown and DivUp differ only in
	 * this constant. {@code idiom} marks a node that stands for a whole
	 * recognized ceiling, whose scaffolding values are absorbed.
	 */
	public record Div(int vn, int[] numerator, int divisor, Direction rounds, boolean idiom) implements Node {
	}

	/** A bitwise combination: exact only when every operand is exact. */
	public record Bitwise(int vn, int[] operands) implements Node {
	}

	/** An ordinary merge: both runs take the same path, so the arms meet. */
	public record Merge(int vn, int[] arms) implements Node {
	}

	/** A recognized clamp under a guard that may round; see {@link RoundingRecognition.GuardedMerge}. */
	public record GuardedMerge(int vn, int guard, int bound, int thenArm, int elseArm, int clampOffset)
			implements Node {
	}

	/** A loop-carried merge under an exit bound that may round; see {@link RoundingRecognition.LoopInduction}. */
	public record LoopMerge(int vn, int bound, int init, int latch, int ivInit, boolean monotone) implements Node {
	}

	/** Soundness backstop for a merge under guards not recognized precisely; see {@link RoundingRecognition.BranchFloor}. */
	public record FloorMerge(int vn, int[] arms, int[] guardSlice) implements Node {
	}

	/**
	 * A read through a computed index. An exact index reads the same cell in both runs, and the
	 * value found there is a fresh unknown as before; a rounded index makes the two runs read
	 * different cells, so the loaded value bears no relation to its intended counterpart and is
	 * Inconsistent. Loads aliased to a dominating store keep the stored value's node instead:
	 * both runs read back what they themselves wrote, whatever the index did.
	 */
	public record Load(int vn, int index) implements Node {
	}

	/**
	 * A call: summaries and callee recursion are keyed by directions, so its transfer lives in Phase 2.
	 * With a {@code component}, {@code vn} is that component of the tuple the call returns (a
	 * destructuring {@code (x, y) = f(...)} reads each one); without, it is the call's own result.
	 */
	public record Call(int vn, SSAAbstractInvokeInstruction site, FieldReference component) implements Node {
	}

	/** The operands whose values flow into the node, in transfer-function order. */
	public static int[] valueOperands(Node n) {
		return switch (n) {
		case Const c -> NONE;
		case Param p -> NONE;
		case Opaque o -> NONE;
		case Add a -> new int[] { a.left(), a.right() };
		case Mul m -> new int[] { m.left(), m.right() };
		case Sub s -> new int[] { s.left(), s.right() };
		case Neg g -> new int[] { g.operand() };
		case Assign a -> new int[] { a.operand() };
		case Div d -> append(d.numerator(), d.divisor());
		case Bitwise b -> b.operands();
		case Merge m -> m.arms();
		case GuardedMerge g -> new int[] { g.bound(), g.thenArm(), g.elseArm() };
		case LoopMerge l -> new int[] { l.init(), l.latch(), l.ivInit() };
		case FloorMerge f -> f.arms();
		case Load l -> NONE;
		case Call c -> {
			int[] uses = new int[c.site().getNumberOfUses()];
			for (int i = 0; i < uses.length; i++) {
				uses[i] = c.site().getUse(i);
			}
			yield uses;
		}
		};
	}

	/** The operands that only select what the node computes: guards, bounds, guard slices. */
	public static int[] guardOperands(Node n) {
		return switch (n) {
		case GuardedMerge g -> new int[] { g.guard() };
		case LoopMerge l -> new int[] { l.bound() };
		case FloorMerge f -> f.guardSlice();
		case Load l -> new int[] { l.index() };
		default -> NONE;
		};
	}

	/** All operands in the order the node's transfer function reads them. */
	public static int[] transferOperands(Node n) {
		return switch (n) {
		case GuardedMerge g -> new int[] { g.guard(), g.bound(), g.thenArm(), g.elseArm() };
		case LoopMerge l -> new int[] { l.bound(), l.init(), l.latch(), l.ivInit() };
		case FloorMerge f -> {
			int[] r = Arrays.copyOf(f.arms(), f.arms().length + f.guardSlice().length);
			System.arraycopy(f.guardSlice(), 0, r, f.arms().length, f.guardSlice().length);
			yield r;
		}
		case Load l -> new int[] { l.index() };
		default -> valueOperands(n);
		};
	}

	private static final int[] NONE = new int[0];

	private static int[] append(int[] xs, int x) {
		int[] r = Arrays.copyOf(xs, xs.length + 1);
		r[xs.length] = x;
		return r;
	}

	private final Map<Integer, Node> byVn;
	private final List<Node> equationOrder;
	private final int[] owner;
	private final int[] returnVns;
	private final NumberedLabeledGraph<Node, EdgeKind> graph;

	RoundingGraph(Map<Integer, Node> byVn, List<Node> equationOrder, int[] owner, int[] returnVns) {
		this.byVn = byVn;
		this.equationOrder = equationOrder;
		this.owner = owner;
		this.returnVns = returnVns;

		SlowSparseNumberedLabeledGraph<Node, EdgeKind> g = new SlowSparseNumberedLabeledGraph<>(EdgeKind.VALUE);
		List<Node> ordered = byVn.values().stream().distinct()
				.sorted((a, b) -> Integer.compare(a.vn(), b.vn())).toList();
		ordered.forEach(g::addNode);
		for (Node n : ordered) {
			for (int o : valueOperands(n)) {
				g.addEdge(byVn.get(owner(o)), n, EdgeKind.VALUE);
			}
			for (int o : guardOperands(n)) {
				g.addEdge(byVn.get(owner(o)), n, EdgeKind.GUARD);
			}
		}
		this.graph = g;
	}

	/**
	 * The node that gives {@code vn} its direction, or null when the value has none: an absorbed
	 * idiom interior, or a value number out of range.
	 */
	public Node node(int vn) {
		return vn > 0 && vn < owner.length ? byVn.get(owner[vn]) : null;
	}

	/** The representative value number that owns {@code vn}'s node (trivial store/load aliasing). */
	public int owner(int vn) {
		return owner[vn];
	}

	/** The nodes that need an equation, in the order Phase 2 should create them. */
	public List<Node> equationOrder() {
		return equationOrder;
	}

	/** Value numbers whose directions meet into the method's return direction. */
	public int[] returnVns() {
		return returnVns;
	}

	/** The materialized graph: an edge per operand, labeled VALUE or GUARD, operand to consumer. */
	public NumberedLabeledGraph<Node, EdgeKind> graph() {
		return graph;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("Q:\n");
		byVn.values().stream().distinct()
				.sorted((a, b) -> Integer.compare(a.vn(), b.vn()))
				.forEach(n -> sb.append("  v").append(n.vn()).append(" = ").append(n).append('\n'));
		return sb.toString();
	}
}

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.json.JSONArray;
import org.json.JSONObject;

import com.certora.wala.analysis.rounding.RoundingAnalysis.RoundingInference.Result;
import com.certora.wala.cast.solidity.util.JSONOutput;
import com.ibm.wala.cast.loader.AstMethod;
import com.ibm.wala.cast.loader.AstMethod.DebuggingInformation;
import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.cast.util.SourceBuffer;
import com.ibm.wala.fixedpoint.impl.DefaultFixedPointSolver;
import com.ibm.wala.fixedpoint.impl.NullaryOperator;
import com.ibm.wala.fixpoint.AbstractOperator;
import com.ibm.wala.fixpoint.AbstractVariable;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.ContextItem;
import com.ibm.wala.ipa.callgraph.ContextKey;
import com.ibm.wala.ipa.callgraph.propagation.ConstantKey;
import com.ibm.wala.ipa.callgraph.propagation.FilteredPointerKey.SingleInstanceFilter;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.graph.labeled.NumberedLabeledGraph;
import com.ibm.wala.util.graph.labeled.SlowSparseNumberedLabeledGraph;

public class RoundingAnalysis {
	private final boolean IMPLICIT_NEITHER = true;

	private final CallGraph CG;
	private final PointerAnalysis<InstanceKey> PA;
	private final RoundingSummary S;

	private final Map<Pair<CGNode, List<Direction>>, RoundingInference.Result> rawResults = HashMapFactory.make();
	private final Map<Pair<CGNode, List<Direction>>, Map<FieldReference, Direction>> directionalCalls = HashMapFactory
			.make();

	private final Map<CGNode, RoundingRecognition> recognitionCache = HashMapFactory.make();
	private final Map<CGNode, RoundingGraph> graphCache = HashMapFactory.make();

	/** Values that only ever say where to read or write; they name a location, so they are exact. */
	private final PositionValues positions;

	public RoundingAnalysis(CallGraph CG, PointerAnalysis<InstanceKey> PA, RoundingSummary S) {
		this.CG = CG;
		this.PA = PA;
		this.S = S;
		this.positions = new PositionValues(CG);
	}

	public RoundingAnalysis(CallGraph CG, PointerAnalysis<InstanceKey> PA) {
		this(CG, PA,  new RoundingSummary.Default());
	}

	/** Phase 1 recognition for this node (cached). */
	RoundingRecognition getRecognition(CGNode n) {
		RoundingRecognition r = recognitionCache.get(n);
		if (r == null) {
			r = new RoundingRecognition(n.getIR(), new RoundingRecognition.Calls() {
				@Override
				public Collection<IR> targets(SSAAbstractInvokeInstruction call) {
					return CG.getPossibleTargets(n, call.getCallSite()).stream()
						.map(CGNode::getIR).filter(Objects::nonNull).collect(Collectors.toList());
				}

				@Override
				public boolean isFloorQuotient(SSAAbstractInvokeInstruction call) {
					return S.isDivOp(call.getCallSite().getDeclaredTarget().getDeclaringClass().getName().toString());
				}
			});
			recognitionCache.put(n, r);
		}
		return r;
	}

	/** Ablation switch for the evaluation: {@code -DdisablePositions=true} turns off
	 *  position inference, so values that only name a location are analyzed as amounts. */
	private static final boolean DISABLE_POSITIONS = Boolean.getBoolean("disablePositions");

	/** Phase 1's graph of this node's operations (cached; direction-independent). */
	public RoundingGraph getGraph(CGNode n) {
		RoundingGraph g = graphCache.get(n);
		if (g == null) {
			Feasibility f = new Feasibility(n, CG, PA);
			g = new RoundingGraphBuilder(n, getRecognition(n), f,
					DISABLE_POSITIONS ? vn -> false : vn -> positions.isPosition(n, vn)).build();
			graphCache.put(n, g);
		}
		return g;
	}

	/**
	 * Phase 2: relational abstract interpretation over Q for one {@code (CGNode, direction
	 * context)}. One dataflow variable per graph node, one equation per node kind's transfer
	 * function, solved to a fixpoint; recognized patterns need no special handling here because
	 * their nodes already list the values they read.
	 */
	public class RoundingInference extends DefaultFixedPointSolver<RoundingInference.RoundingVariable> {

		private final Set<RoundingInference.RoundingVariable> result = HashSetFactory.make();

		private class RoundingVariable extends AbstractVariable<RoundingVariable> {
			int vn;
			Direction state;

			public RoundingVariable(int vn, Direction state) {
				this.vn = vn;
				this.state = state;
			}

			@Override
			public void copyState(RoundingVariable v) {
				state = v.state;
			}

			@Override
			public String toString() {
				return "<" + vn + ":" + state + ">";
			}
		}

		private final CGNode n;
		private final IR ir;
		private final List<Direction> parameters;
		private final RoundingGraph Q;
		private final Map<Integer, RoundingVariable> vars = HashMapFactory.make();

		/** The direction of a value here: null when it maps to no node (absorbed idiom scaffolding). */
		private Direction stateOf(int vn) {
			RoundingGraph.Node nd = Q.node(vn);
			return nd == null ? null : vars.get(nd.vn()).state;
		}

		/** Pins a value the graph calls exact: positions, comparisons, remainders. */
		private final NullaryOperator<RoundingVariable> exactOperator = new NullaryOperator<RoundingVariable>() {
			@Override
			public byte evaluate(RoundingVariable lhs) {
				if (lhs.state != Direction.Neither) {
					lhs.state = Direction.Neither;
					return CHANGED;
				}
				return NOT_CHANGED;
			}

			@Override
			public int hashCode() {
				return 668976;
			}

			@Override
			public boolean equals(Object o) {
				return o == this;
			}

			@Override
			public String toString() {
				return "constant Neither";
			}
		};

		/**
		 * The transfer function of one graph node, a direct transcription of technical.tex §4.4:
		 * the {@code switch} is exhaustive over the sealed node kinds, so a new pattern that
		 * reaches Phase 2 without a transfer is a compile error.
		 */
		private class NodeOperator extends AbstractOperator<RoundingVariable> {
			private final RoundingGraph.Node node;

			NodeOperator(RoundingGraph.Node node) {
				this.node = node;
			}

			@Override
			public byte evaluate(RoundingVariable lhs, RoundingVariable[] rhs) {
				Direction d = transfer(rhs);
				if (d == null || d == lhs.state) {
					return NOT_CHANGED;
				}
				lhs.state = d;
				return CHANGED;
			}

			private Direction transfer(RoundingVariable[] rhs) {
				return switch (node) {
				case RoundingGraph.Add a -> combine(rhs[0].state, rhs[1].state, false);
				case RoundingGraph.Mul m -> combine(rhs[0].state, rhs[1].state, false);
				case RoundingGraph.Sub s -> combine(rhs[0].state, rhs[1].state, true);

				case RoundingGraph.Neg g -> rhs[0].state == null ? null : rhs[0].state.flip();
				case RoundingGraph.Assign a -> rhs[0].state;

				case RoundingGraph.Div div -> {
					// divUp/divDown(N1 * ... * Nk, D): the rounding's own direction, combined
					// with the factors' directions and the flipped direction of the divisor.
					Direction d = div.rounds();
					for (int i = 0; i < rhs.length; i++) {
						if (rhs[i].state == null) {
							yield null;
						}
						d = d.combine(i == rhs.length - 1 ? rhs[i].state.flip() : rhs[i].state);
					}
					yield d;
				}

				case RoundingGraph.Bitwise b -> {
					// Not a numeric function of its operands' magnitudes: exact operands give
					// an exact result, anything else is Inconsistent.
					Direction d = Direction.Neither;
					for (RoundingVariable v : rhs) {
						if (v.state == null) {
							yield null;
						}
						if (v.state != Direction.Neither) {
							d = Direction.Inconsistent;
						}
					}
					yield d;
				}

				case RoundingGraph.Merge m -> {
					Direction d = rhs[0].state == null ? Direction.Neither : rhs[0].state;
					for (int i = 1; i < rhs.length; i++) {
						d = d.meet(rhs[i].state == null ? Direction.Neither : rhs[i].state);
					}
					yield d;
				}

				case RoundingGraph.GuardedMerge g -> {
					// rhs = [guard, bound, thenArm, elseArm]. When a compared operand rounds,
					// the integer and real runs can take different arms; a recognized clamp
					// gives the divergence contribution precisely.
					Direction dGuard = rhs[0].state;
					Direction dBound = rhs[1].state;
					Direction dThen = rhs[2].state;
					Direction dElse = rhs[3].state;
					if (dGuard == null || dBound == null || dThen == null || dElse == null) {
						yield null;
					}
					Direction aligned = dThen.meet(dElse);
					if (dGuard == Direction.Neither && dBound == Direction.Neither) {
						// Guard is exact: both runs always take the same arm.
						yield aligned;
					} else if (dGuard == Direction.Down && dBound == Direction.Neither) {
						// Clamp under a round-down guard: in the divergence gap the written
						// value is bound + k against a real value in (bound, bound + 1).
						Direction divergence = g.clampOffset() >= 1 ? Direction.Up : Direction.Down;
						yield aligned.combine(divergence);
					} else {
						// Rounded guard we cannot resolve precisely.
						yield Direction.Inconsistent;
					}
				}

				case RoundingGraph.LoopMerge l -> {
					// rhs = [bound, init, latch, ivInit]. Trip count grows with (bound - ivInit):
					// a rounded-down bound gives fewer/equal integer iterations, a rounded-down
					// induction start gives more.
					Direction dBound = rhs[0].state;
					Direction dInit = rhs[1].state;
					Direction dIvInit = rhs[3].state;
					if (dBound == null || dInit == null || dIvInit == null) {
						yield null;
					}
					Direction tripDir = dBound.combine(dIvInit.flip());
					if (l.monotone()) {
						// Final value = init + step*(trip count).
						yield dInit.combine(tripDir);
					} else if (tripDir == Direction.Neither) {
						// No trip-count divergence: ordinary loop-carried merge.
						Direction dLatch = rhs[2].state;
						yield dLatch == null ? null : dInit.meet(dLatch);
					} else {
						yield Direction.Inconsistent;
					}
				}

				case RoundingGraph.FloorMerge f -> {
					// rhs = [arms..., guardSlice...]. If any value feeding a controlling guard
					// rounds, the two runs can take different paths; if every guard input is
					// exact, the runs stay aligned and it is an ordinary meet.
					int operandCount = f.arms().length;
					boolean sliceIncomplete = false;
					for (int i = operandCount; i < rhs.length; i++) {
						Direction d = rhs[i].state;
						if (d == null) {
							sliceIncomplete = true;
						} else if (d != Direction.Neither) {
							yield Direction.Inconsistent;
						}
					}
					if (sliceIncomplete) {
						yield null;
					}
					Direction r = null;
					for (int i = 0; i < operandCount; i++) {
						Direction d = rhs[i].state;
						if (d == null) {
							yield null;
						}
						r = r == null ? d : r.meet(d);
					}
					yield r == null ? Direction.Neither : r;
				}

				case RoundingGraph.Load l -> {
					// rhs = [index]. An exact index reads the same cell in both runs and the
					// value found there is a fresh unknown, as before; a rounded index makes
					// the runs read different cells, so the loaded value is Inconsistent.
					Direction idx = rhs[0].state;
					yield idx == null ? null
							: idx == Direction.Neither ? Direction.Neither : Direction.Inconsistent;
				}

				case RoundingGraph.Const c -> throw new AssertionError(node);
				case RoundingGraph.Param p -> throw new AssertionError(node);
				case RoundingGraph.Opaque o -> throw new AssertionError(node);
				case RoundingGraph.Call c -> throw new AssertionError(node);
				};
			}

			private Direction combine(Direction l, Direction r, boolean flipRight) {
				if (l == null || r == null) {
					return null;
				}
				return l.combine(flipRight ? r.flip() : r);
			}

			@Override
			public int hashCode() {
				return 31 * node.vn() + node.getClass().hashCode();
			}

			@Override
			public boolean equals(Object o) {
				return o instanceof NodeOperator && ((NodeOperator) o).node == node;
			}

			@Override
			public String toString() {
				return "transfer " + node;
			}
		}

		public RoundingInference(List<Direction> parameters, Set<Pair<CGNode, List<Direction>>> ongoing, CGNode n)
				throws CancelException {
			ir = n.getIR();
			this.parameters = parameters;
			this.n = n;
			this.Q = getGraph(n);

			class CallOperator extends AbstractOperator<RoundingVariable> {
				private final SSAAbstractInvokeInstruction callInst;
				/** The tuple component this equation defines, or null for the call's own result. */
				private final FieldReference component;

				public CallOperator(SSAAbstractInvokeInstruction inst, FieldReference component) {
					this.callInst = inst;
					this.component = component;
				}

				/**
				 * The callee's direction for what this equation defines. Components are matched by
				 * position, since caller and callee may declare different component types. A tuple
				 * seen as one value rounds as all its components together.
				 */
				private Direction resultOf(Map<FieldReference, Direction> callee) {
					if (component == null) {
						return callee.containsKey(null) ? callee.get(null)
								: callee.values().stream().reduce(Direction.Neither, Direction::meet);
					}
					for (Map.Entry<FieldReference, Direction> e : callee.entrySet()) {
						if (e.getKey() != null && e.getKey().getName().equals(component.getName())) {
							return e.getValue();
						}
					}
					return Direction.Neither;
				}

				@Override
				public byte evaluate(RoundingVariable lhs, RoundingVariable[] rhs) {
					List<Direction> args = new ArrayList<>(callInst.getNumberOfUses());
					for (int i = 0; i < callInst.getNumberOfUses(); i++) {
						args.add(stateOf(callInst.getUse(i)));
					}

					Direction d = Direction.Neither;
					RoundingSummary.Value summary = S.get(new RoundingSummary.Key(
							callInst.getCallSite().getDeclaredTarget().getDeclaringClass().getName().toString(), args));
					if (summary != null) {
						d = summary.result;

					} else {
						for (CGNode cgn : CG.getPossibleTargets(n, callInst.getCallSite())) {
							Pair<CGNode, List<Direction>> key = Pair.make(cgn, args);
							if (!ongoing.contains(key)) {
								if (!directionalCalls.containsKey(key)) {
									Set<Pair<CGNode, List<Direction>>> x = HashSetFactory.make(ongoing);
									x.add(key);
									try {
										@SuppressWarnings("unused")
										RoundingInference child = new RoundingInference(args, x, cgn);
									} catch (CancelException e) {
										// without assertions this used to continue with the call
										// silently treated as exact
										throw new RuntimeException("analysis of " + cgn + " was cancelled", e);
									}
								}
								if (directionalCalls.containsKey(key)) {
									d = d.meet(resultOf(directionalCalls.get(key)));
								}
							}
						}
					}

					if (d != lhs.state) {
						lhs.state = d;
						return CHANGED;
					} else {
						return NOT_CHANGED;
					}
				}

				@Override
				public int hashCode() {
					return callInst.hashCode() * 17 + Objects.hashCode(component);
				}

				@Override
				public boolean equals(Object o) {
					return o != null && o.getClass() == getClass() && callInst.equals(((CallOperator) o).callInst)
							&& Objects.equals(component, ((CallOperator) o).component);
				}

				@Override
				public String toString() {
					return "call " + callInst + (component == null ? "" : " ." + component.getName());
				}

			}

			// One variable per graph node. A node without an equation keeps its initial state,
			// which is where a parameter's context direction enters the system.
			for (RoundingGraph.Node nd : Q.graph()) {
				Direction init;
				if (ir.getSymbolTable().isConstant(nd.vn())) {
					init = Direction.Neither;
				} else if (nd.vn() <= parameters.size()) {
					init = parameters.get(nd.vn() - 1);
				} else {
					init = Direction.Neither;
				}
				vars.put(nd.vn(), new RoundingVariable(nd.vn(), init));
			}
			for (int vn : Q.returnVns()) {
				result.add(vars.get(vn));
			}

			for (RoundingGraph.Node nd : Q.equationOrder()) {
				RoundingVariable lhs = vars.get(nd.vn());
				if (nd instanceof RoundingGraph.Opaque) {
					newStatement(lhs, exactOperator, false, false);
					continue;
				}
				int[] operands = RoundingGraph.transferOperands(nd);
				RoundingVariable[] rhs = makeStmtRHS(operands.length);
				for (int i = 0; i < operands.length; i++) {
					rhs[i] = vars.get(Q.node(operands[i]).vn());
				}
				AbstractOperator<RoundingVariable> op = nd instanceof RoundingGraph.Call c
						? new CallOperator(c.site(), c.component())
						: new NodeOperator(nd);
				newStatement(lhs, op, rhs, false, false);
			}

			solve(null);

			Pair<CGNode, List<Direction>> key = Pair.make(n, parameters);
			rawResults.put(key, getRoundingResult());
			directionalCalls.put(key, getResultOrResults());
		}

		@Override
		protected RoundingVariable[] makeStmtRHS(int size) {
			return new RoundingVariable[size];
		}

		@Override
		protected void initializeVariables() {
			// handled in the constructor
		}

		@Override
		protected void initializeWorkList() {
			addAllStatementsToWorkList();
		}

		@Override
		public int hashCode() {
			return ir.getMethod().hashCode();
		}

		@Override
		public boolean equals(Object o) {
			return o == this;
		}

		public Direction getResult() {
			return result.stream().filter(x -> x.state != null).map(x -> x.state).reduce(Direction::meet)
					.orElse(Direction.Neither);
		}

		private Map<FieldReference, Direction> unpackTuple(RoundingVariable x) {
			int maybeTuple = x.vn;
			Map<FieldReference, Direction> result = HashMapFactory.make();
			ir.iterateAllInstructions().forEachRemaining(inst -> {
				if (inst instanceof SSAPutInstruction) {
					SSAPutInstruction p = (SSAPutInstruction) inst;
					if (p.getRef() == maybeTuple) {
						Direction d = stateOf(p.getVal());
						if (d != null) {
							if (result.containsKey(p.getDeclaredField())) {
								result.put(p.getDeclaredField(), d.meet(result.get(p.getDeclaredField())));
							} else {
								result.put(p.getDeclaredField(), d);
							}
						}
					}
				}
			});
			return result;
		}

		private Map<FieldReference, Direction> reduceTuples(Map<FieldReference, Direction> l,
				Map<FieldReference, Direction> r) {
			Map<FieldReference, Direction> result = HashMapFactory.make();
			for (FieldReference lk : l.keySet()) {
				if (!r.containsKey(lk)) {
					result.put(lk, l.get(lk));
				} else {
					result.put(lk, l.get(lk).meet(r.get(lk)));
				}
			}
			for (FieldReference rk : r.keySet()) {
				if (!l.containsKey(rk)) {
					result.put(rk, r.get(rk));
				}
			}
			return result;
		}

		public Map<FieldReference, Direction> getResults() {
			return result.stream().map(this::unpackTuple).reduce(this::reduceTuples).orElse(Collections.emptyMap());
		}

		public Map<FieldReference, Direction> getResultOrResults() {
			Map<FieldReference, Direction> result = getResults();
			if (result.isEmpty()) {
				result = Collections.singletonMap(null, getResult());
			}
			return result;
		}

		@Override
		public String toString() {
			return super.toString() + "returning " + result;
		}

		public interface Result {
			Direction[][] getOperandRounding();

			Direction[][] getResultRounding();

			JSONObject toJSON();

			NumberedLabeledGraph<JSONObject, Position> toGraph();

			JSONObject makeGraph(NumberedLabeledGraph<JSONObject,Position> g, Map<Pair<CGNode, List<Direction>>, JSONObject> startedSoFar);

			Map<FieldReference, Direction> getReturnRounding();
		}

		public Result getRoundingResult() {
			Direction[][] operands = new Direction[ir.getInstructions().length][];
			Direction[][] results = new Direction[ir.getInstructions().length][];
			for (SSAInstruction inst : ir.getInstructions()) {
				if (inst != null) {
					Direction[] uses = operands[inst.iIndex()] = new Direction[inst.getNumberOfUses()];
					for (int i = 0; i < uses.length; i++) {
						uses[i] = stateOf(inst.getUse(i));
					}
					Direction[] defs = results[inst.iIndex()] = new Direction[inst.getNumberOfDefs()];
					for (int i = 0; i < defs.length; i++) {
						defs[i] = stateOf(inst.getDef(i));
					}
				}
			}
			return new Result() {
				@Override
				public Direction[][] getOperandRounding() {
					return operands;
				}

				@Override
				public Direction[][] getResultRounding() {
					return results;
				}

				@Override
				public String toString() {
					NumberedLabeledGraph<JSONObject,Position> out = new SlowSparseNumberedLabeledGraph<>();
					makeGraph(out, HashMapFactory.make());
					StringBuffer sb = new StringBuffer();
					out.forEach(n -> {
						sb.append(out.getNumber(n) + ": function " + n.getString("method"));
						if (n.has("methodPosition")) {
							sb.append(" (").append(n.getString("methodPosition")).append(")");
						}
						sb.append('\n');

						JSONArray parameters = n.getJSONArray("parameters");
						for(int i = 0; i < parameters.length(); i++) {
							JSONObject p = parameters.getJSONObject(i);
							if (p.has("source")) {
								sb.append("   ").append(p.getString("position")).append(" ").append(p.getString("source")).append(" --> ").append(p.get("rounding")).append('\n');
							}
						}

						JSONObject roundings = n.getJSONObject("roundings");
						for(String pos : roundings.keySet()) {
							JSONObject r = roundings.getJSONObject(pos);
							if (Direction.Neither != r.get("rounding")) {
								sb.append(" ").append(pos).append(": ").append(r.get("source")).append(" --> ").append(r.get("rounding"));
								if (r.has("expr")) {
									sb.append(" (use in ").append(r.get("expr")).append(")");
								}
								sb.append('\n');
							}
						}

						if (n.has("return")) {
							sb.append(" return " + n.get("return"));
							sb.append("\n");
						}

						if (out.getSuccNodeCount(n) > 0) {
							sb.append(" --> ").append(out.getSuccNodeNumbers(n));
						}

						sb.append("\n");
					});

					return sb.toString();
				}

				public JSONObject toJSON() {
					JSONObject o = new JSONObject();
					o.put("method", ir.getMethod().toString());
					DebuggingInformation dbg = ((AstMethod) ir.getMethod()).debugInfo();
					if (ir.getMethod() instanceof AstMethod) {
						o.put("methodPosition", dbg.getCodeBodyPosition().getURL().getPath() + ":" + JSONOutput.toLocalPos(dbg.getCodeBodyPosition()));
					}
					JSONArray params;
					o.put("parameters", params = new JSONArray(ir.getNumberOfParameters()));
					for (int i = 0; i < ir.getNumberOfParameters(); i++) {
						try {
							JSONObject p = new JSONObject();
							p.put("rounding", String.valueOf(stateOf(i + 1)));
							params.put(p);
							Position pos = dbg.getParameterPosition(i);
							if (pos != null) {
								p.put("position", JSONOutput.toLocalPos(pos));
								p.put("source", new SourceBuffer(pos).toString());
							}
							ContextItem a = n.getContext().get(ContextKey.PARAMETERS[i]);
							if (a instanceof SingleInstanceFilter && ((SingleInstanceFilter)a).getInstance() instanceof ConstantKey ) {
								p.put("value", String.valueOf(((ConstantKey<?>)((SingleInstanceFilter)a).getInstance()).getValue()));
							}
						} catch (IOException e) {
							assert false : e;
						}
					}
					JSONObject roundings = new JSONObject();
					o.put("roundings", roundings);
					for (int i = 0; i < operands.length; i++) {
						if (operands[i] != null) {
							for (int j = 0; j < operands[i].length; j++) {
								if (operands[i][j] != null) {
									expressionToJSON(operands, dbg, roundings, i, j, true);
								}
							}
						}
						if (results[i] != null) {
							for (int j = 0; j < results[i].length; j++) {
								if (results[i][j] != null) {
									expressionToJSON(results, dbg, roundings, i, j, false);
								}
							}
						}
					}
					Map<FieldReference, Direction> ret = getResultOrResults();
					o.put("return", String.valueOf(ret.containsKey(null)? ret.get(null): ret));

					return o;
				}

				public NumberedLabeledGraph<JSONObject,Position> toGraph() {
					NumberedLabeledGraph<JSONObject,Position> out = new SlowSparseNumberedLabeledGraph<>();
					makeGraph(out, HashMapFactory.make());
					return out;
				}

				public JSONObject makeGraph(NumberedLabeledGraph<JSONObject,Position> g, Map<Pair<CGNode, List<Direction>>, JSONObject> startedSoFar) {
					Pair<CGNode, List<Direction>> me = Pair.make(n, parameters);
					if (!startedSoFar.containsKey(me)) {
						DebuggingInformation dbg = ((AstMethod) ir.getMethod()).debugInfo();
						JSONObject thisOne = toJSON();
						startedSoFar.put(me, thisOne);
						g.addNode(thisOne);

						ir.iterateAllInstructions().forEachRemaining(inst -> {
							if (inst instanceof SSAInvokeInstruction) {
								List<Direction> args = new ArrayList<>(inst.getNumberOfUses());
								for (int i = 0; i < inst.getNumberOfUses(); i++) {
									args.add(stateOf(inst.getUse(i)));
								}
								for (CGNode callee : CG.getPossibleTargets(n,
										((SSAInvokeInstruction) inst).getCallSite())) {
									Pair<CGNode, List<Direction>> key = Pair.make(callee, args);
									if (rawResults.containsKey(key) && !startedSoFar.containsKey(key)) {
									  g.addEdge(thisOne, rawResults.get(key).makeGraph(g, startedSoFar), dbg.getInstructionPosition(inst.iIndex()));
									}
								}
							}
						});
						return thisOne;
					} else {
						return startedSoFar.get(me);
					}

				}

				private void expressionToJSON(Direction[][] data, DebuggingInformation dbg, JSONObject roundings,
						int i, int j, boolean use) {
					if (ir.getMethod() instanceof AstMethod) {
						Position p = use? dbg.getOperandPosition(i, j): dbg.getInstructionPosition(i);
						if (p != null && (!IMPLICIT_NEITHER || data[i][j] != Direction.Neither)) {
							try {
								String k = JSONOutput.toLocalPos(p);
								JSONObject x = new JSONObject();
								roundings.put(k, x);
								x.put("rounding", data[i][j].toString());
								x.put("source", new SourceBuffer(p).toString());
								if (use) {
									x.put("expr", new SourceBuffer(dbg.getInstructionPosition(i)).toString());
								}
							} catch (IOException e) {
								assert false : e;
							}
						}
					}
				}

				@Override
				public Map<FieldReference, Direction> getReturnRounding() {
					return getResultOrResults();
				}
			};
		}
	}

	public Result analyzeForNode(CallGraph cg, CGNode n) throws CancelException {
		List<Direction> params = IntStream.range(0, n.getMethod().getNumberOfParameters()).mapToObj(i -> Direction.Neither).toList();
		Pair<CGNode,List<Direction>> key = Pair.make(n, params);
		if (! rawResults.containsKey(key)) {
			RoundingInference ri = new RoundingInference(params, HashSetFactory.make(), n);
			Result G = ri.getRoundingResult();
			return G;
		} else {
			return rawResults.get(key);
		}
	}
}

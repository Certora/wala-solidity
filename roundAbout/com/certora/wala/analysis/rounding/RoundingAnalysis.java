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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jspecify.annotations.Nullable;

import com.certora.wala.analysis.defuse.DefUseGraph;
import com.certora.wala.analysis.rounding.RoundingAnalysis.RoundingInference.Result;
import com.certora.wala.cast.solidity.util.JSONOutput;import com.google.common.collect.Streams;
import com.ibm.wala.cast.ir.ssa.CAstBinaryOp;
import com.ibm.wala.cast.loader.AstMethod;
import com.ibm.wala.cast.loader.AstMethod.DebuggingInformation;
import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.cast.util.SourceBuffer;
import com.ibm.wala.cfg.Util;
import com.ibm.wala.cfg.cdg.ControlDependenceGraph;
import com.ibm.wala.dataflow.ssa.SSAInference;
import com.ibm.wala.fixpoint.AbstractOperator;
import com.ibm.wala.fixpoint.AbstractVariable;
import com.ibm.wala.fixpoint.IVariable;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.ContextItem;
import com.ibm.wala.ipa.callgraph.ContextKey;
import com.ibm.wala.ipa.callgraph.propagation.ConstantKey;
import com.ibm.wala.ipa.callgraph.propagation.FilteredPointerKey.SingleInstanceFilter;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
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
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSACFG.BasicBlock;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInstruction.Visitor;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.NullProgressMonitor;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;import com.ibm.wala.util.collections.Pair;import com.ibm.wala.util.graph.dominators.Dominators;import com.ibm.wala.util.graph.labeled.NumberedLabeledGraph;
import com.ibm.wala.util.graph.labeled.SlowSparseNumberedLabeledGraph;
import com.ibm.wala.util.graph.traverse.DFS;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetUtil;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.OrdinalSet;

public class RoundingAnalysis {
	private final boolean IMPLICIT_NEITHER = true;
	
	private final CallGraph CG;
	private final PointerAnalysis<InstanceKey> PA;
	private final RoundingSummary S;
	
	private final Map<Pair<CGNode, List<Direction>>, RoundingInference.Result> rawResults = HashMapFactory.make();
	private final Map<Pair<CGNode, List<Direction>>, Map<FieldReference, Direction>> directionalCalls = HashMapFactory
			.make();

	private final Map<CGNode, RoundingRecognition> recognitionCache = HashMapFactory.make();

	public RoundingAnalysis(CallGraph CG, PointerAnalysis<InstanceKey> PA, RoundingSummary S) {
		this.CG = CG;
		this.PA = PA;
		this.S = S;
	}

	public RoundingAnalysis(CallGraph CG, PointerAnalysis<InstanceKey> PA) {
		this(CG, PA,  new RoundingSummary.Default());
	}

	/** Phase 1 recognition for this node (cached). */
	RoundingRecognition getRecognition(CGNode n) {
		RoundingRecognition r = recognitionCache.get(n);
		if (r == null) {
			r = new RoundingRecognition(n.getIR());
			recognitionCache.put(n, r);
		}
		return r;
	}
	
	private class MaybeBooleanVariable extends AbstractVariable<MaybeBooleanVariable> {
		private boolean hasValue;
		private boolean value;
		
		@Override
		public void copyState(MaybeBooleanVariable v) {
			hasValue = v.hasValue;
			value = v.value;
		}

		private void set(boolean v) {
			value = v;
			hasValue = true;
		}
		
		@Override
		public String toString() {
			return "mbv: " + hasValue + ":" + value;
		}
	}
	
	public class RoundingInference extends SSAInference<RoundingInference.RoundingVariable> {

		class TrivialBooleanConstantPropagation extends SSAInference<MaybeBooleanVariable> {
			private final CGNode boolNode;
			private final SymbolTable S;
			private final Boolean[] knownParams;
			private final Set<CGNode> ongoing;

			static Boolean getValue(MaybeBooleanVariable v) {
				return v.hasValue? v.value: null;
			}

			Boolean getConstant(int v) {
				return getValue(getVariable(v));
			}
			
			public TrivialBooleanConstantPropagation(CGNode n, Boolean[] knownParams, Set<CGNode> ongoing) {
				this.knownParams = knownParams;
				this.boolNode = n;
				this.ongoing = ongoing;
				this.S = n.getIR().getSymbolTable();
			    init(n.getIR(), this.new MaybeBooleanVarFactory(), this.new MaybeBooleanOperatorFactory());
			    try {
					solve(new NullProgressMonitor());
				} catch (CancelException e) {
					assert false : e;
				}
			}

			public class MaybeBooleanOperatorFactory extends SSAInstruction.Visitor implements OperatorFactory<MaybeBooleanVariable> {
				AbstractOperator<MaybeBooleanVariable> op;
							
				@Override
				public void visitBinaryOp(SSABinaryOpInstruction inst) {
					if (inst.getOperator() == CAstBinaryOp.EQ || inst.getOperator() == CAstBinaryOp.NE) {
						op = new AbstractOperator<MaybeBooleanVariable>() {

							@Override
							public byte evaluate(MaybeBooleanVariable lhs, MaybeBooleanVariable[] rhs) {
								Boolean left = getValue(rhs[0]);
								Boolean right = getValue(rhs[1]);
								if (left == null || right == null) {
									return NOT_CHANGED;
								} else {
									boolean eq = left.equals(right);
									Boolean lhv = getValue(lhs);
									if (lhv == null || lhv.booleanValue() != eq) {
										lhs.set(inst.getOperator() == CAstBinaryOp.EQ? eq: !eq);
										return CHANGED;
									} else {
										return NOT_CHANGED;
									}
								}
							}

							@Override
							public int hashCode() {
								return inst.iIndex();
							}

							@Override
							public boolean equals(Object o) {
								return getClass()==o.getClass() && hashCode() == o.hashCode();
							}

							@Override
							public String toString() {
								return inst.getUse(0) + " " + inst.getOperator() + " " + inst.getUse(1);
							}
						};
					}
				}

				@Override
				public void visitUnaryOp(SSAUnaryOpInstruction instruction) {
					if (instruction.getOpcode() == IUnaryOpInstruction.Operator.NEG) {
						op = new AbstractOperator<MaybeBooleanVariable>() {

							@Override
							public byte evaluate(@Nullable MaybeBooleanVariable lhs, MaybeBooleanVariable[] rhs) {
								Boolean rv = getValue(rhs[0]);
								Boolean oldLv = getValue(lhs);
								if (rv == null) {
									return NOT_CHANGED;
								} else if (oldLv == null || !rv.equals(!oldLv.booleanValue())) {						
									lhs.set(! rv.booleanValue());
									return CHANGED;
								} else {
									return NOT_CHANGED;								
								}
							}

							@Override
							public int hashCode() {
								return instruction.iIndex();
							}

							@Override
							public boolean equals(Object o) {
								return getClass()==o.getClass() && hashCode() == o.hashCode();
							}

							@Override
							public String toString() {
								return "!" + instruction.getUse(0);
							}
						};
					}
				}

				@Override
				public void visitInvoke(SSAInvokeInstruction instruction) {
					Boolean[] params = new Boolean[instruction.getNumberOfUses()];

					op = new AbstractOperator<MaybeBooleanVariable>() {
						@Override
						public byte evaluate(MaybeBooleanVariable lhs, MaybeBooleanVariable[] rhs) {
							for(int i = 0; i < instruction.getNumberOfUses(); i++) {
								params[i] = getValue(getVariable(instruction.getUse(i)));
							}

							Boolean r = null;
							for (CGNode callee : CG.getPossibleTargets(boolNode, instruction.getCallSite())) {
								if (ongoing.contains(callee)) {
									return NOT_CHANGED;
								}
								Set<CGNode> x = HashSetFactory.make(ongoing);
								x.add(callee);
								Boolean b = new TrivialBooleanConstantPropagation(callee, params, x).getReturnIfAny();
								if (b == null) {
									return NOT_CHANGED;
								} else {
									if (r == null) {
										r = b;
									} else {
										if (!r.equals(b)) {
											return NOT_CHANGED;
										}
									}
								}
							}
							if (r != null) {
								Boolean lv = getValue(lhs);
								if (lv == null) {
									lhs.set(r);
									return CHANGED;
								} else {
									assert lv.equals(r);
									return NOT_CHANGED;
								}
							} else {
								return NOT_CHANGED;
							}
						}
						
						@Override
						public int hashCode() {
							return instruction.iIndex();
						}

						@Override
						public boolean equals(Object o) {
							return getClass()==o.getClass() && hashCode() == o.hashCode();
						}

						@Override
						public String toString() {
							return "call: " + instruction;
						}
					};
				}

				@Override
				public void visitPhi(SSAPhiInstruction instruction) {
					op = new AbstractOperator<MaybeBooleanVariable>() {

						@Override
						public byte evaluate(MaybeBooleanVariable lhs, MaybeBooleanVariable[] rhs) {	
							Set<Boolean> bs = HashSetFactory.make();
							for(int i = 0; i < instruction.getNumberOfUses(); i++) {
								if (!deadPhiRvals.containsKey(instruction) || !deadPhiRvals.get(instruction).contains(instruction.getUse(i))) {
									bs.add(rhs[i].hasValue? rhs[i].value: null);
								}
							}
							if (bs.size() != 1 || bs.contains(null)) {
								return NOT_CHANGED;
							} else {
								Boolean lv = getValue(lhs);
								Boolean rv = bs.iterator().next();
								if (rv.equals(lv)) {
									return NOT_CHANGED; 
								} else {
									assert lv == null;
									lhs.set(rv);
									return CHANGED;
								}
							}
						}

						@Override
						public int hashCode() {
							return instruction.iIndex();
						}

						@Override
						public boolean equals(Object o) {
							return getClass()==o.getClass() && hashCode() == o.hashCode();
						}

						@Override
						public String toString() {
							return "phi: " + instruction;
						}
					};
				}
				
				@Override
				public void visitPi(SSAPiInstruction instruction) {
					op = new AbstractOperator<MaybeBooleanVariable>() {

						@Override
						public byte evaluate(@Nullable MaybeBooleanVariable lhs, MaybeBooleanVariable[] rhs) {
							Boolean rv = getValue(rhs[0]);
							Boolean oldLv = getValue(lhs);
							if (rv == null) {
								return NOT_CHANGED;
							} else if (oldLv == null || !rv.equals(oldLv.booleanValue())) {						
								lhs.set(rv.booleanValue());
								return CHANGED;
							} else {
								return NOT_CHANGED;								
							}
						}

						@Override
						public int hashCode() {
							return instruction.iIndex();
						}

						@Override
						public boolean equals(Object o) {
							return getClass()==o.getClass() && hashCode() == o.hashCode();
						}

						@Override
						public String toString() {
							return "" + instruction.getUse(0);
						}
					};
				}

				@Override
				public AbstractOperator<MaybeBooleanVariable> get(SSAInstruction inst) {
					if (!boolNode.equals(n) || !deadBlocks.contains(boolNode.getIR().getBasicBlockForInstruction(inst))) {
						op = null;
						inst.visit(this);
						return op;
					} else {
						return null;
					}
				}			
			}
			
			public class MaybeBooleanVarFactory implements VariableFactory<MaybeBooleanVariable> {
				private final IntSet params = IntSetUtil.make(S.getParameterValueNumbers());

				@Override
				public IVariable<MaybeBooleanVariable> makeVariable(int valueNumber) {
					MaybeBooleanVariable v = new MaybeBooleanVariable();
					
					if (S.isBooleanConstant(valueNumber)) {
						v.set((Boolean)S.getConstantValue(valueNumber));
					} else if (S.isNumberConstant(valueNumber)) {
						v.set(0 != ((Number)S.getConstantValue(valueNumber)).intValue());
					} else if (knownParams != null && params.contains(valueNumber)) {
						if (knownParams[valueNumber-1] != null) {
							v.set(knownParams[valueNumber-1]);
						}
					} else if (boolNode.getContext() != null && params.contains(valueNumber)) {
						ContextItem val = boolNode.getContext().get(ContextKey.PARAMETERS[valueNumber-1]);
						if (val instanceof SingleInstanceFilter && ((SingleInstanceFilter)val).getInstance() instanceof ConstantKey ) {
							Object c = ((ConstantKey<?>)((SingleInstanceFilter)val).getInstance()).getValue();
							if (c instanceof Boolean) {
								v.set((Boolean)c);
							} else if (c instanceof Number) {
								v.set(0 != ((Number)c).intValue());
							}
						}
					}
					
					return v;
				}
			}
			
			@Override
			protected MaybeBooleanVariable[] makeStmtRHS(int size) {
				return new MaybeBooleanVariable[size];
			}

			@Override
			protected void initializeVariables() {
				// handled by init()
			}

			@Override
			protected void initializeWorkList() {
				addAllStatementsToWorkList();
			}
			
			private Boolean getReturnIfAny() {
				Set<SSAInstruction> rets = Streams.stream(boolNode.getIR().iterateAllInstructions()).filter(inst -> inst instanceof SSAReturnInstruction).collect(Collectors.toSet());
				if (rets.stream().anyMatch(inst -> inst.getNumberOfUses() < 1)) {
					return null;
				} else {
					Set<Boolean> rs = rets.stream().map(inst -> TrivialBooleanConstantPropagation.getValue(getVariable(inst.getUse(0)))).collect(Collectors.toSet());
					if (rs.size() != 1 || rs.contains(null)) {
						return null;
					} else {
						return rs.iterator().next();
					}
				}
			}
		}

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

		private final AbstractOperator<RoundingVariable> phiOperator = new AbstractOperator<RoundingVariable>() {
			@Override
			public byte evaluate(RoundingVariable lhs, RoundingVariable[] rhs) {
				Direction d = rhs[0].state == null ? Direction.Neither : rhs[0].state;
				for (int i = 1; i < rhs.length; i++) {
					d = d.meet(rhs[i].state == null ? Direction.Neither : rhs[i].state);
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
				return 34659878;
			}

			@Override
			public boolean equals(Object o) {
				return o == this;
			}

			@Override
			public String toString() {
				return "rounding phi operator";
			}
		};

		private final AbstractOperator<RoundingVariable> piOperator = new AbstractOperator<RoundingVariable>() {
			@Override
			public byte evaluate(RoundingVariable lhs, RoundingVariable[] rhs) {
				Direction d = rhs[0].state;

				if (d != lhs.state) {
					lhs.state = d;
					return CHANGED;
				} else {
					return NOT_CHANGED;
				}
			}

			@Override
			public int hashCode() {
				return 763469878;
			}

			@Override
			public boolean equals(Object o) {
				return o == this;
			}

			@Override
			public String toString() {
				return "rounding phi operator";
			}
		};

		private final AbstractOperator<RoundingVariable> flipOperator = new AbstractOperator<RoundingVariable>() {
			@Override
			public byte evaluate(RoundingVariable lhs, RoundingVariable[] rhs) {
				if (rhs[0] != null && rhs[0].state != null) {
					Direction d = rhs[0].state.flip();

					if (d != lhs.state) {
						lhs.state = d;
						return CHANGED;
					}
				}
				return NOT_CHANGED;
			}

			@Override
			public int hashCode() {
				return 234235346;
			}

			@Override
			public boolean equals(Object o) {
				return o == this;
			}

			@Override
			public String toString() {
				return "rounding flip operator";
			}
		};

		private AbstractOperator<RoundingVariable> assignOperator = new AbstractOperator<RoundingVariable>() {
			@Override
			public byte evaluate(RoundingVariable lhs, RoundingVariable[] rhs) {
				if (lhs.state != rhs[0].state) {
					lhs.state = rhs[0].state;
					return CHANGED;
				} else {
					return NOT_CHANGED;
				}
			}

			@Override
			public int hashCode() {
				return 87798708;
			}

			@Override
			public boolean equals(Object o) {
				return o == this;
			}

			@Override
			public String toString() {
				return "rounding assign operator";
			}

		};

		private class BinaryOperator extends AbstractOperator<RoundingVariable> {
			private final boolean flipRight;
			private final Direction init;

			public BinaryOperator(boolean flipRight, Direction init) {
				this.flipRight = flipRight;
				this.init = init;
			}

			@Override
			public byte evaluate(RoundingVariable lhs, RoundingVariable[] rhs) {
				if (rhs[0].state != null && rhs[1].state != null) {
					Direction d = rhs[0].state;
					d = d == null ? d : d.combine(flipRight ? rhs[1].state.flip() : rhs[1].state);
					d = d == null ? init : init.combine(d);

					if (d != lhs.state) {
						lhs.state = d;
						return CHANGED;
					}
				}

				return NOT_CHANGED;
			}

			@Override
			public int hashCode() {
				return 6745836 * init.hashCode() * (flipRight ? 1 : -1);
			}

			@Override
			public boolean equals(Object o) {
				return o.getClass() == this.getClass() && init == ((BinaryOperator) o).init
						&& flipRight == ((BinaryOperator) o).flipRight;
			}

			@Override
			public String toString() {
				return "rounding bin op " + init + " " + flipRight;
			}

		}

		class ConstantOperator extends AbstractOperator<RoundingVariable> {
			private final Direction d;

			public ConstantOperator(Direction d) {
				this.d = d;
			}

			@Override
			public byte evaluate(RoundingVariable lhs, RoundingVariable[] rhs) {
				if (lhs.state != d) {
					lhs.state = d;
					return CHANGED;
				} else {
					return NOT_CHANGED;
				}
			}

			@Override
			public int hashCode() {
				return d.hashCode() * 668976;
			}

			@Override
			public boolean equals(Object o) {
				return o != null && getClass() == o.getClass() && d.equals(((ConstantOperator) o).d);
			}

			@Override
			public String toString() {
				return "constant " + d;
			}
		};

		/** A ceiling idiom recognized by Phase 1: divUp(dividend, divisor). */
		private class RoundUpIdiomOperator extends AbstractOperator<RoundingVariable> {
			private final int dividend;
			private final int divisor;

			RoundUpIdiomOperator(int dividend, int divisor) {
				this.dividend = dividend;
				this.divisor = divisor;
			}

			@Override
			public byte evaluate(RoundingVariable lhs, RoundingVariable[] rhs) {
				Direction dd = getVariable(dividend).state;
				Direction dv = getVariable(divisor).state;
				if (dd != null && dv != null) {
					Direction d = Direction.Up.combine(dd).combine(dv.flip());
					if (d != lhs.state) {
						lhs.state = d;
						return CHANGED;
					}
				}
				return NOT_CHANGED;
			}

			@Override
			public int hashCode() {
				return 41 * dividend + divisor;
			}

			@Override
			public boolean equals(Object o) {
				return o instanceof RoundUpIdiomOperator && ((RoundUpIdiomOperator) o).dividend == dividend
						&& ((RoundUpIdiomOperator) o).divisor == divisor;
			}

			@Override
			public String toString() {
				return "round-up idiom divUp(" + dividend + ", " + divisor + ")";
			}
		}

		private final CGNode n;
		private final IR ir;
		private final DefUse du;
		private final DefUseGraph dug;
		private final List<Direction> parameters;
		private final RoundingRecognition recognition;

		private ControlDependenceGraph<ISSABasicBlock> cdg = null;
		
		private Set<ISSABasicBlock> deadBlocks = HashSetFactory.make();
		private Map<SSAPhiInstruction,MutableIntSet> deadPhiRvals = HashMapFactory.make();
		
		private TrivialBooleanConstantPropagation booleanConstants;
		
		private void gatherControlDeps(int vn, boolean trueBranch) {
			du.getUses(vn).forEachRemaining(inst -> { 
				if (inst instanceof SSAConditionalBranchInstruction) {
					SSACFG cfg = ir.getControlFlowGraph();
					if (cdg == null) {
						cdg = new ControlDependenceGraph<>(cfg, true);
					}
					
					SSACFG.BasicBlock pb = cfg.getBlockForInstruction(inst.iIndex());					
					ISSABasicBlock db = trueBranch? Util.getNotTakenSuccessor(cfg, pb): Util.getTakenSuccessor(cfg, pb);
					cfg.getSuccNodes(pb).forEachRemaining(sb -> {
						if (cdg.getEdgeLabels(pb, sb).contains(db)) {
							deadBlocks.addAll(DFS.getReachableNodes(cdg, Collections.singleton(sb)));
						}
					});
				}
			});
		}
		
		private RoundingSummary.Value getSummaryIfAny(SSAAbstractInvokeInstruction callInst) {
			List<Direction> args = new ArrayList<>(callInst.getNumberOfUses());
			for (int i = 0; i < callInst.getNumberOfUses(); i++) {
				args.add(getVariable(callInst.getUse(i)).state);
			}

			return S.get(new RoundingSummary.Key(callInst.getCallSite().getDeclaredTarget().getDeclaringClass().getName().toString(), args));
		}

		public RoundingInference(List<Direction> parameters, Set<Pair<CGNode, List<Direction>>> ongoing, CGNode n)
				throws CancelException {
			ir = n.getIR();
			du = n.getDU();
			this.parameters = parameters;
			this.n = n;
			this.dug = new DefUseGraph(ir);
			this.recognition = getRecognition(n);

			computeDeadBlocks(n);

			this.booleanConstants = new TrivialBooleanConstantPropagation(n, null, Collections.emptySet());

			class CallOperator extends AbstractOperator<RoundingVariable> {
				private final SSAInvokeInstruction callInst;

				public CallOperator(SSAInvokeInstruction inst) {
					this.callInst = inst;
				}

				@Override
				public byte evaluate(RoundingVariable lhs, RoundingVariable[] rhs) {
					List<Direction> args = new ArrayList<>(callInst.getNumberOfUses());
					for (int i = 0; i < callInst.getNumberOfUses(); i++) {
						args.add(getVariable(callInst.getUse(i)).state);
					}

					Direction d = Direction.Neither;
					RoundingSummary.Value summary = getSummaryIfAny(callInst);
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
										assert false : e;
									}
								}
								if (directionalCalls.containsKey(key) && directionalCalls.get(key).containsKey(null)) {
									d = d.meet(directionalCalls.get(key).get(null));
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
					return callInst.hashCode() * 17;
				}

				@Override
				public boolean equals(Object o) {
					return o != null && o.getClass() == getClass() && callInst.equals(((CallOperator) o).callInst);
				}

				@Override
				public String toString() {
					return "call " + callInst;
				}

			}

			class RoundingOperatorFactory extends SSAInstruction.Visitor implements OperatorFactory<RoundingVariable> {
				private AbstractOperator<RoundingVariable> result;

				@Override
				public AbstractOperator<RoundingVariable> get(SSAInstruction instruction) {
					result = null;
					if (! deadBlocks.contains(ir.getControlFlowGraph().getBlockForInstruction(instruction.iIndex()))) {
						instruction.visit(this);
					}
					return result;
				}

				@Override
				public void visitBinaryOp(SSABinaryOpInstruction instruction) {
					IBinaryOpInstruction.IOperator op = instruction.getOperator();
					if (op == IBinaryOpInstruction.Operator.ADD) {
						result = new BinaryOperator(false, Direction.Neither);

					} else if (op == IBinaryOpInstruction.Operator.MUL || op == IShiftInstruction.Operator.SHL) {
						result = new BinaryOperator(false, Direction.Neither);

					} else if (op == IBinaryOpInstruction.Operator.DIV) {
						Direction d = recognition.divDirection(instruction);

						result = new BinaryOperator(true, d);

					} else if (op == IBinaryOpInstruction.Operator.SUB) {

						result = new BinaryOperator(true, Direction.Neither);

					} else {
						result = new ConstantOperator(Direction.Neither);
					}
				}

				@Override
				public void visitUnaryOp(SSAUnaryOpInstruction instruction) {
					IUnaryOpInstruction.IOperator op = instruction.getOpcode();
					if (op == IUnaryOpInstruction.Operator.NEG) {
						result = flipOperator;
					} else {
						result = assignOperator;
					}
				}

				@Override
				public void visitCheckCast(SSACheckCastInstruction instruction) {
					result = piOperator;
				}

				@Override
				public void visitPhi(SSAPhiInstruction instruction) {
					int[] ops = recognition.roundUpIdiomOperands(instruction);
					if (ops != null) {
						result = new RoundUpIdiomOperator(ops[0], ops[1]);
					} else {
						result = phiOperator;
					}
				}

				@Override
				public void visitPi(SSAPiInstruction instruction) {
					result = piOperator;
				}

				@Override
				public void visitInvoke(SSAInvokeInstruction inst) {
					if (inst.hasDef()) {
						result = new CallOperator(inst);
					}
				}

			}

			class RoundingVariableFactory implements VariableFactory<RoundingVariable> {
				
				class TrivialAliasing {
					int[] mapping = new int[ ir.getSymbolTable().getMaxValueNumber()+1 ];
					int[] reverseMapping = new int[ ir.getSymbolTable().getMaxValueNumber()+1 ];
					Dominators<ISSABasicBlock> d = Dominators.make(ir.getControlFlowGraph(), ir.getControlFlowGraph().entry());
					
					private boolean escapes(int n) {
						return new Visitor() {
							boolean escapes = false;
							
							{
								if (du.getUses(n) != null) {
									du.getUses(n).forEachRemaining(s -> s.visit(this));
								}
							}
							
							@Override
							public void visitArrayStore(SSAArrayStoreInstruction instruction) {
								if (instruction.getValue() == n) {
									escapes = true;
								}
							}

							@Override
							public void visitPut(SSAPutInstruction instruction) {
								if (instruction.getVal() == n) {
									escapes = true;
								}
							}

							@Override
							public void visitInvoke(SSAInvokeInstruction instruction) {
								escapes = true;
							}
						}.escapes;
					}
										
					private boolean dominates(SSAInstruction before, SSAInstruction after) {
						BasicBlock bbb = ir.getControlFlowGraph().getBlockForInstruction(before.iIndex());
						BasicBlock bba = ir.getControlFlowGraph().getBlockForInstruction(after.iIndex());
						if (bba == bbb) {
							return before.iIndex() < after.iIndex();
						} else {
							return d.isDominatedBy(bbb, bba);
						}
					}
					
					private SSAInstruction getDefIfAny(SSAInstruction use) {
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
											if (dominates(useInst, instruction)) {
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
					
					{
						MutableIntSet escapees = IntSetUtil.make();
						for(int i = 1; i < mapping.length; i++) {
							mapping[i] = i;
							reverseMapping[i] = i;
							if (escapes(i)) {
								escapees.add(i);
							}
						}
						
						ir.iterateNormalInstructions().forEachRemaining(inst -> { 
							SSAInstruction def = getDefIfAny(inst);
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
				}
				
				TrivialAliasing aliasAnalysis = new TrivialAliasing();

				private boolean hasReturn(int vn) {
					Iterator<SSAInstruction> is = du.getUses(vn);
					while (is.hasNext()) {
						if (is.next() instanceof SSAReturnInstruction) {
							return true;
						}
					}
					return false;
				}

				@Override
				public IVariable<RoundingVariable> makeVariable(int valueNumber) {
					RoundingVariable v;
					
					if (aliasAnalysis.mapping[valueNumber] < valueNumber) {
						return getVariable(aliasAnalysis.mapping[valueNumber]);
					} else if (aliasAnalysis.reverseMapping[valueNumber] < valueNumber) {
						return getVariable(aliasAnalysis.reverseMapping[valueNumber]);
					} 
					
					if (ir.getSymbolTable().isConstant(valueNumber)) {
						v = new RoundingVariable(valueNumber, Direction.Neither);

					} else if (valueNumber <= parameters.size()) {
						v = new RoundingVariable(valueNumber, parameters.get(valueNumber - 1));

					} else if (du.getDef(valueNumber) instanceof SSAAbstractInvokeInstruction) {
						v = new RoundingVariable(valueNumber, Direction.Neither);

					} else {
						v = new RoundingVariable(valueNumber, Direction.Neither);
					}

					if (hasReturn(valueNumber)) {
						result.add(v);
					}

					return v;
				}

			}

			init(ir, new RoundingVariableFactory(), new RoundingOperatorFactory());
			solve(null);

			Pair<CGNode, List<Direction>> key = Pair.make(n, parameters);
			rawResults.put(key, getRoundingResult());
			directionalCalls.put(key, getResultOrResults());
		}

		private Object returnsConstant(CGNode n) {
			SymbolTable s = n.getIR().getSymbolTable();
			Set<Object> cs = Streams.stream(n.getIR().iterateAllInstructions())
				.filter(inst -> inst instanceof SSAReturnInstruction)
				.map(inst -> inst.getNumberOfUses() > 0? inst.getUse(0): -1)
				.map(v -> v==-1 || !s.isConstant(v)? null: s.getConstantValue(v))
				.collect(Collectors.toSet());
			if (cs.size() == 1) {
				return cs.iterator().next();
			} else {
				return null;
			}
		}
		
		private void computeDeadBlocks(CGNode n) {
			boolean more = false;
			SSACFG cfg = n.getIR().getControlFlowGraph();
		
			int old = deadBlocks.size();
			for(int i = 0; i < n.getMethod().getNumberOfParameters(); i++) {
				final int stupidi = i;
				ContextItem k = n.getContext().get(ContextKey.PARAMETERS[i]);
				if (k instanceof SingleInstanceFilter && ((SingleInstanceFilter)k).getInstance() instanceof ConstantKey) {
					InstanceKey ik = ((SingleInstanceFilter)k).getInstance();
					du.getUses(i+1).forEachRemaining(use -> { 
						if (use instanceof SSABinaryOpInstruction && ((SSABinaryOpInstruction)use).getOperator() == CAstBinaryOp.EQ) {
							int otherV = use.getUse(0) == stupidi+1? use.getUse(1): use.getUse(0);
							PointerKey otherKey = PA.getHeapModel().getPointerKeyForLocal(n, otherV);
							OrdinalSet<InstanceKey> otherObjs = PA.getPointsToSet(otherKey);
							if (otherObjs.contains(ik) && otherObjs.size()==1) {
								gatherControlDeps(use.getDef(), false);
							} else {
								if (!otherObjs.isEmpty() &&
									Streams.stream(otherObjs)
										.filter(ok -> ok.getConcreteType().equals(ik.getConcreteType()) &&
												      (!(ok instanceof ConstantKey<?>) ||
												       ((ConstantKey<?>)ik).getValue().equals(((ConstantKey<?>)ok).getValue())))
										.findAny()
										.isEmpty()) {
									}
								gatherControlDeps(use.getDef(), true);
							}
						}
					});
				}
			}
			
			ir.iterateAllInstructions().forEachRemaining(inst -> { 
				if (inst instanceof SSAAbstractInvokeInstruction) {
					Set<Object> constants = CG.getPossibleTargets(n, ((SSAAbstractInvokeInstruction)inst).getCallSite()).stream().map(callee -> returnsConstant(callee)).collect(Collectors.toSet());
					if (constants.size() == 1) {
						Object v = constants.iterator().next();
						if (Boolean.TRUE.equals(v)) {
							gatherControlDeps(inst.getDef(), false);							
						} else if (Boolean.FALSE.equals(v)) {
							gatherControlDeps(inst.getDef(), true);							
						} 
					}
				}
			});
			more |= deadBlocks.size() != old;
			
			Set<ISSABasicBlock> newDeadBlocks = HashSetFactory.make(deadBlocks);
			while (! newDeadBlocks.isEmpty()) {
				Set<ISSABasicBlock> nextDeadBlocks = HashSetFactory.make();
				newDeadBlocks.stream().forEach(bb -> {
					cfg.getSuccNodes(bb).forEachRemaining(sb -> {
						int whichV = Util.whichPred(cfg, bb, sb);
						sb.iteratePhis().forEachRemaining(phi -> {
							Set<Object> values = HashSetFactory.make();
							for(int i = 0; i < phi.getNumberOfUses(); i++) {
								if (i != whichV && n.getIR().getSymbolTable().isConstant(phi.getUse(i))) {
									values.add(n.getIR().getSymbolTable().getConstantValue(phi.getUse(i)));
								}
							}
							if (values.size() == 1) {
								Object v = values.iterator().next();
								du.getUses(phi.getDef()).forEachRemaining(inst -> { 
									if (inst instanceof SSAConditionalBranchInstruction) {
										if (Boolean.FALSE.equals(v)) {
											nextDeadBlocks.add(Util.getNotTakenSuccessor(cfg, cfg.getBlockForInstruction(inst.iIndex())));
										} else if (Boolean.TRUE.equals(v)) {
											nextDeadBlocks.add(Util.getTakenSuccessor(cfg, cfg.getBlockForInstruction(inst.iIndex())));
										}
									}
								});
							}
						});
					});
				});
				more |= deadBlocks.addAll(nextDeadBlocks);
				newDeadBlocks = nextDeadBlocks;
			}
			
			deadBlocks.forEach(db -> {
				cfg.getSuccNodes(db).forEachRemaining(sb -> {
					int deadBack = Util.whichPred(cfg, db, sb);
					sb.iteratePhis().forEachRemaining(phi -> { 
						int rv = phi.getUse(deadBack);
						if (! deadPhiRvals.containsKey(phi)) {
							deadPhiRvals.put(phi, IntSetUtil.make());
						}
						deadPhiRvals.get(phi).add(rv);
					});
				});
			});

			if (more) {
				booleanConstants = new TrivialBooleanConstantPropagation(n, null, Collections.emptySet());
			}
		}
		
		@Override
		protected RoundingVariable[] makeStmtRHS(int size) {
			return new RoundingVariable[size];
		}

		@Override
		protected void initializeVariables() {
			// handled in init(...)

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
						Direction d = getVariable(p.getVal()).state;
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
						uses[i] = getVariable(inst.getUse(i)).state;
					}
					Direction[] defs = results[inst.iIndex()] = new Direction[inst.getNumberOfDefs()];
					for (int i = 0; i < defs.length; i++) {
						defs[i] = getVariable(inst.getDef(i)).state;
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
							p.put("rounding", String.valueOf(getVariable(i + 1).state));
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
									args.add(getVariable(inst.getUse(i)).state);
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
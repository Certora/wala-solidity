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

import org.jspecify.annotations.Nullable;

import com.google.common.collect.Streams;
import com.ibm.wala.cast.ir.ssa.CAstBinaryOp;
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
import com.ibm.wala.shrike.shrikeBT.IUnaryOpInstruction;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPiInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.NullProgressMonitor;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.graph.traverse.DFS;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetUtil;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.OrdinalSet;

/**
 * What this calling context makes infeasible in one method: the blocks that cannot execute, the
 * phi operands that arrive only from those blocks, and the boolean values that are constant.
 * None of it reads a {@link Direction}, so it is computed once per {@link CGNode} and shared by
 * every direction context that solves over the same node's graph.
 */
public class Feasibility {
	private final CGNode n;
	private final CallGraph CG;
	private final PointerAnalysis<InstanceKey> PA;
	private final IR ir;
	private final DefUse du;

	private final Set<ISSABasicBlock> deadBlocks = HashSetFactory.make();
	private final Map<SSAPhiInstruction, MutableIntSet> deadPhiRvals = HashMapFactory.make();

	private ControlDependenceGraph<ISSABasicBlock> cdg = null;

	private final TrivialBooleanConstantPropagation booleanConstants;

	public Feasibility(CGNode n, CallGraph CG, PointerAnalysis<InstanceKey> PA) {
		this.n = n;
		this.CG = CG;
		this.PA = PA;
		this.ir = n.getIR();
		this.du = n.getDU();
		computeDeadBlocks(n);
		this.booleanConstants = new TrivialBooleanConstantPropagation(n, null, Collections.emptySet());
	}

	/** Blocks this context can never execute. */
	public Set<ISSABasicBlock> deadBlocks() {
		return deadBlocks;
	}

	/** Phi operands that arrive only from dead blocks, keyed by their phi. */
	public Map<SSAPhiInstruction, MutableIntSet> deadPhiRvals() {
		return deadPhiRvals;
	}

	/** The constant value of a boolean, or null when it is not constant here. */
	public Boolean booleanConstant(int vn) {
		return booleanConstants.getConstant(vn);
	}

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
		SSACFG cfg = n.getIR().getControlFlowGraph();

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
							// the other operand can only be this constant: the equality always holds
							gatherControlDeps(use.getDef(), false);
						} else if (!otherObjs.isEmpty() &&
							Streams.stream(otherObjs)
								.filter(ok -> ok.getConcreteType().equals(ik.getConcreteType()) &&
										      (!(ok instanceof ConstantKey<?>) ||
										       ((ConstantKey<?>)ik).getValue().equals(((ConstantKey<?>)ok).getValue())))
								.findAny()
								.isEmpty()) {
							// no value the other operand can take could equal the constant: the
							// equality never holds. When it merely might not hold, neither side
							// is dead and nothing may be pruned.
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
			nextDeadBlocks.removeAll(deadBlocks);
			deadBlocks.addAll(nextDeadBlocks);
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
	}

	static class MaybeBooleanVariable extends AbstractVariable<MaybeBooleanVariable> {
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

		public TrivialBooleanConstantPropagation(CGNode node, Boolean[] knownParams, Set<CGNode> ongoing) {
			this.knownParams = knownParams;
			this.boolNode = node;
			this.ongoing = ongoing;
			this.S = node.getIR().getSymbolTable();
		    init(node.getIR(), this.new MaybeBooleanVarFactory(), this.new MaybeBooleanOperatorFactory());
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
}

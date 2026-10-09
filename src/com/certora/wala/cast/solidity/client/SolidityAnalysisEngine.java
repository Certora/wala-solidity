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
package com.certora.wala.cast.solidity.client;

import static com.certora.wala.cast.solidity.loader.SolidityLoader.allSupers;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.certora.wala.cast.solidity.ipa.callgraph.EnumValueContextSelector;
import com.certora.wala.cast.solidity.ipa.callgraph.LinkedEntrypoint;
import com.certora.wala.cast.solidity.ipa.callgraph.SolidityAddressInstantiator;
import com.certora.wala.cast.solidity.loader.EnumType;
import com.certora.wala.cast.solidity.loader.InterfaceType;
import com.certora.wala.cast.solidity.loader.SolidityLoader;
import com.certora.wala.cast.solidity.loader.SolidityLoader.TypedCodeBody;
import com.certora.wala.cast.solidity.translator.InterproceduralConstantFoldingRewriter;
import com.certora.wala.cast.solidity.translator.SolidityAstTranslator;
import com.certora.wala.cast.solidity.tree.SolidityCAstType;
import com.certora.wala.cast.solidity.types.SolidityTypes;
import com.certora.wala.cast.solidity.util.Configuration;
import com.certora.wala.cast.solidity.util.Configuration.Conf;
import com.certora.wala.cast.solidity.util.SpecFileJSON;
import com.ibm.wala.analysis.reflection.FactoryBypassInterpreter;
import com.ibm.wala.cast.ipa.callgraph.AstContextInsensitiveSSAContextInterpreter;
import com.ibm.wala.cast.ir.ssa.AstIRFactory;
import com.ibm.wala.cast.ir.ssa.AstIRFactory.AstDefaultIRFactory;
import com.ibm.wala.cast.ir.translator.AstTranslator;
import com.ibm.wala.cast.loader.AstMethod;
import com.ibm.wala.cast.loader.AstMethod.Retranslatable;
import com.ibm.wala.cast.loader.CAstAbstractModuleLoader.DynamicCodeBody;
import com.ibm.wala.cast.loader.SingleClassLoaderFactory;
import com.ibm.wala.cast.tree.CAst;
import com.ibm.wala.cast.tree.CAstControlFlowMap;
import com.ibm.wala.cast.tree.CAstEntity;
import com.ibm.wala.cast.tree.CAstNode;
import com.ibm.wala.cast.tree.CAstType;
import com.ibm.wala.cast.tree.impl.CAstImpl;
import com.ibm.wala.cast.tree.impl.CAstOperator;
import com.ibm.wala.cfg.ControlFlowGraph;
import com.certora.wala.cast.solidity.translator.BaseCallSiteReference;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.client.AbstractAnalysisEngine;
import com.ibm.wala.core.util.strings.Atom;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.PropagationCallGraphBuilder;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ipa.callgraph.propagation.cfa.DelegatingSSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.cfa.ZeroXInstanceKeys;
import com.ibm.wala.ipa.callgraph.propagation.cfa.nCFABuilder;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ipa.cha.SolidityClassHierarchy;
import com.ibm.wala.shrike.shrikeBT.IInvokeInstruction.Dispatch;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.IRView;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAOptions;
import com.ibm.wala.ssa.SSAOptions.DefaultValues;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.MonitorUtil.IProgressMonitor;
import com.ibm.wala.util.WalaRuntimeException;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.intset.OrdinalSet;

public abstract class SolidityAnalysisEngine<A> extends AbstractAnalysisEngine<InstanceKey, CallGraphBuilder<InstanceKey>, A> {

	public final Map<String, Long> statistics = HashMapFactory.make();
	
	protected final File confFile;
	protected final Conf conf;
	protected SingleClassLoaderFactory loaders;
	protected final SpecFileJSON spec;
	
	protected SolidityAnalysisEngine(File confFile, SpecFileJSON spec) throws FileNotFoundException {
		this.confFile = confFile;
		this.spec = spec;
		this.conf = Configuration.getConf(confFile);
	}

	protected SolidityAnalysisEngine(File confFile) throws FileNotFoundException {
		this(confFile, null);	
	}
	
	@Override
	protected PropagationCallGraphBuilder getCallGraphBuilder(IClassHierarchy cha, AnalysisOptions options,
			IAnalysisCacheView analysisCache) {
		
		new SolidityAddressInstantiator(cha);
		
		Util.setNativeSpec(null);
		Util.addDefaultSelectors(options, cha);
		Util.addDefaultBypassLogic(options, Util.class.getClassLoader(), cha);

		ContextSelector appSelector = null;
		SSAContextInterpreter appInterpreter = null;
		SSAPropagationCallGraphBuilder cgBuilder = 
		  new nCFABuilder(2, SolidityLoader.solidity.getFakeRootMethod(cha, options, analysisCache), options, analysisCache, appSelector, appInterpreter) {		
			
			@Override
			protected boolean handleCall(CGNode node, IClass recv, SSAAbstractInvokeInstruction instruction,
					InstanceKey[][] invs, PointerKey uniqueCatch, InstanceKey[] v) {
				final CallSiteReference site = instruction.getCallSite();
				
				IClass targetClass = v.length>0? v[0].getConcreteType(): cha.lookupClass(site.getDeclaredTarget().getDeclaringClass());
				if (targetClass != null && cha.isSubclassOf(targetClass, cha.lookupClass(SolidityTypes.codeBody))) {
					final Set<CGNode> targets = HashSetFactory.make();
					final Set<IMethod> targetMethods = HashSetFactory.make();

					if (site instanceof BaseCallSiteReference) {
						// Base.f(...): bound to the named function's own body, no dispatch on self
						IClass named = cha.lookupClass(site.getDeclaredTarget().getDeclaringClass());
						if (named instanceof DynamicCodeBody && ((DynamicCodeBody)named).getCodeBody() != null) {
							targetMethods.add(((DynamicCodeBody)named).getCodeBody());
						}
					} else if ((recv = v[0].getConcreteType()) != null &&
							recv instanceof TypedCodeBody && 
							(((TypedCodeBody)recv).isVirtual() || 
									site.getInvocationCode() == Dispatch.SPECIAL ||
									((TypedCodeBody)recv).getSelfType() instanceof InterfaceType)) {

						InstanceKey selfKey = v[0];
						PointerKey selfField = getPointerKeyFactory().getPointerKeyForInstanceField(selfKey, selfKey.getConcreteType().getField(Atom.findOrCreateUnicodeAtom("self")));	
						OrdinalSet<InstanceKey> selfKeys = getPointerAnalysis().getPointsToSet(selfField);
						selfKeys.forEach(new Consumer<InstanceKey>() {
							private IClass getFunctionType(IClass sc) {
								String nm = sc.getName() + "." + ((TypedCodeBody)selfKey.getConcreteType()).functionName();
								IClass ff = getClassHierarchy().lookupClass(TypeReference.findOrCreate(SolidityTypes.solidity, TypeName.string2TypeName(nm)));
								return ff;
							}

							List<AstMethod> getSuperCalls(InstanceKey fk) {
								Set<IClass> allSupers = allSupers(fk.getConcreteType()).stream().map(x -> getFunctionType(x)).collect(Collectors.toSet());			
								List<AstMethod> ms = allSupers.stream().filter(m -> m instanceof DynamicCodeBody).map(m -> ((DynamicCodeBody)m).getCodeBody()).collect(Collectors.toList());
								IClassHierarchy cha = node.getClassHierarchy();
								ms.remove(null);
								if (ms.size() == 1) {
									return ms;
								} else {
									return ms.stream().filter(m -> m != null)
											.filter(m -> {
												IClass mc = cha.lookupClass(((TypedCodeBody)m.getDeclaringClass()).getSelf());
												// keep m only if no other candidate overrides it (is declared in a
												// subtype of m's contract): the most-derived override, as Solidity
												// dispatches. The test used to be inverted, which kept overridden
												// middle classes and left supers.get(0) to pick in hash order.
												return ms.stream().filter(o -> o != null).noneMatch(o -> {
													IClass oc = cha.lookupClass(((TypedCodeBody)o.getDeclaringClass()).getSelf());
													return o!=m && (oc.getAllImplementedInterfaces().contains(mc) || cha.isAssignableFrom(mc, oc));
												});
											}).toList();
								}
							}
						
							private void addSuperCalls(InstanceKey sk) {
								List<AstMethod> supers = getSuperCalls(sk);
								if (! supers.isEmpty()) {
									targetMethods.add(supers.get(0));
								}								
							}
							
							@Override
							public void accept(InstanceKey sk) {
								if (site.getInvocationCode() == Dispatch.SPECIAL) {
									addSuperCalls(sk);
								} else {
									IClass sc = sk.getConcreteType();
									IClass ff = getFunctionType(sc);
									if (ff instanceof DynamicCodeBody && ((DynamicCodeBody)ff).getCodeBody() != null) {
										targetMethods.add(((DynamicCodeBody)ff).getCodeBody());
									} else {
										// inherited methods
										addSuperCalls(sk);
									}
								}
							}
						});
 
					} else {
						if (recv instanceof DynamicCodeBody) {
							targetMethods.add(((DynamicCodeBody)recv).getCodeBody());
						}
					}

					for(IMethod targetMethod : targetMethods) {
						Context targetContext = contextSelector.getCalleeTarget(node, site, targetMethod, v);
						try {
							targets.add(getCallGraph().findOrCreateNode(targetMethod, targetContext));
						} catch (CancelException e) {
							assert false : e;
						}
					}

					if (! targets.isEmpty()) {
						targets.forEach(target -> {
							processResolvedCall(node, instruction, target, invs, uniqueCatch);
							if (!haveAlreadyVisited(target)) {
								markDiscovered(target);
							}
						});
					}

					return true;
				} else {
					return super.handleCall(node, recv, instruction, invs, uniqueCatch, v);
				}
			}
		};

		cgBuilder.setContextInterpreter(
				new DelegatingSSAContextInterpreter(
						new FactoryBypassInterpreter(options, analysisCache), 
						new AstContextInsensitiveSSAContextInterpreter(options, analysisCache) {

							@Override
							public IR getIR(CGNode node) {
								return getAnalysisCache().getIR(node.getMethod(), node.getContext());
							}

							@Override
							public IRView getIRView(CGNode node) {
								return getIR(node);
							}

							@Override
							public ControlFlowGraph<SSAInstruction, ISSABasicBlock> getCFG(CGNode N) {
								return getIR(N).getControlFlowGraph();
							}

							@Override
							public DefUse getDU(CGNode node) {
								return new DefUse(getIR(node));
							}

						}));

		cgBuilder.setInstanceKeys(new ZeroXInstanceKeys(options, cha, cgBuilder.getContextInterpreter(), ZeroXInstanceKeys.ALLOCATIONS));

		cgBuilder.setContextSelector(new EnumValueContextSelector(cgBuilder.getContextSelector()));

		return cgBuilder;
	}

	@Override
	public IClassHierarchy buildClassHierarchy() {
		long start = System.nanoTime();
		IClassHierarchy cha;
		try {
			cha = new SolidityClassHierarchy(scope, loaders, null, new ConcurrentHashMap<>(), ClassHierarchy.MissingSuperClassHandling.NONE);
			setClassHierarchy(cha);
			
			SolidityLoader solidityLoader = (SolidityLoader) cha.getLoader(SolidityTypes.solidity);
			solidityLoader.getModulesWithParseErrors().forEachRemaining(m -> {
				solidityLoader.getMessages(m).forEach(msg -> {
					System.err.println(msg);
				});
			});

			solidityLoader.getModulesWithWarnings().forEachRemaining(m -> {
				solidityLoader.getMessages(m).forEach(msg -> {
					System.err.println(msg);
				});
			});

			return cha;
		} catch (ClassHierarchyException e) {
			assert false : e;
			throw new WalaRuntimeException("failed to create class hierarchy", e);
		} finally {
			statistics.put("build_cha", System.nanoTime() - start);
		}
	}

	private AnalysisOptions makeOptions() {
		AnalysisOptions options = new AnalysisOptions();
		options.getSSAOptions().setDefaultValues(new DefaultValues() {

			@Override
			public int getDefaultValue(SymbolTable symtab, int valueNumber) {
				return symtab.getConstant(0);
			} 
			
		});
		
		options.setUseConstantSpecificKeys(true);
		
		return options;
		
	}

	public boolean checkEnumParameters(IMethod target) {
		for(int i = 0; i < target.getNumberOfParameters(); i++) {
			IClass pt = getClassHierarchy().lookupClass(target.getParameterType(i));
			if (pt != null && getClassHierarchy().isAssignableFrom(getClassHierarchy().lookupClass(SolidityTypes.enm), pt)) {
				return true;
			}
		}

		return false;
	}

	private boolean shouldRetranslate(IMethod method, Context context) {
		return checkEnumParameters(method);
	}
	

	  @Override
	  public IAnalysisCacheView makeDefaultCache() {
	    return new AnalysisCacheImpl(new AstDefaultIRFactory<IMethod>(new AstIRFactory<IMethod>() {
			@Override
			public boolean contextIsIrrelevant(IMethod method) {
				return !checkEnumParameters(method);
			}
				    	
	    }) {
			@Override
			public boolean contextIsIrrelevant(IMethod target) {
				return !checkEnumParameters(target);
			}

			@Override
			public ControlFlowGraph<?, ?> makeCFG(IMethod method, Context context) {
				if (shouldRetranslate(method, context)) {
					return super.makeCFG(method, context);					
				} else {
					return super.makeCFG(method, context);
				}
			}
			
			class SolidityEnumConstantRewriter extends InterproceduralConstantFoldingRewriter {

				public SolidityEnumConstantRewriter(CAstEntity fun, Context context, CAst Ast) {
					super(fun, context, Ast);
				}

				
				@Override
				protected CAstNode copyNodes(CAstNode root, CAstControlFlowMap cfg, NonCopyingContext context,
						Map<Pair<CAstNode, NoKey>, CAstNode> nodeMap) {
					CAstNode newRoot = super.copyNodes(root, cfg, context, nodeMap);
					
					if (newRoot.getKind() == CAstNode.BINARY_EXPR && 
						newRoot.getChild(1).getKind() == CAstNode.CONSTANT &&
						newRoot.getChild(2).getKind() == CAstNode.CONSTANT) {
						Object left = newRoot.getChild(1).getValue();
						Object right = newRoot.getChild(2).getValue();
						boolean numeric = left instanceof Number && right instanceof Number;
						CAstNode result = null;
						if (newRoot.getChild(0).equals(CAstOperator.OP_EQ)) {
							result = Ast.makeConstant(left.equals(right));
						} else if (newRoot.getChild(0).equals(CAstOperator.OP_NE)) {
							result = Ast.makeConstant(! left.equals(right));
						} else if (numeric && newRoot.getChild(0).equals(CAstOperator.OP_MOD)) {
							result = Ast.makeConstant(((Number)left).intValue() % ((Number)right).intValue());
						}
						
					    if (result != null) {
					    	nodeMap.put(Pair.make(root, context.key()), result);
					    	return result;
					    }
						
					} else if (newRoot.getKind() == CAstNode.BINARY_EXPR && newRoot.getChild(0).equals(CAstOperator.OP_EQ)) {
						CAstType lct = fun.getNodeTypeMap().getNodeType(root.getChild(1));
						TypeReference ltr = SolidityCAstType.getIRType(lct);
						IClassHierarchy cha = getClassHierarchy();
						IClass lc = cha.lookupClass(ltr);
						if (lc != null && 
							cha.isAssignableFrom(cha.lookupClass(SolidityTypes.enm), lc) 
							&& (newRoot.getChild(1).getKind() == CAstNode.CONSTANT ||
								newRoot.getChild(2).getKind() == CAstNode.CONSTANT)) {
							
							CAstNode left;
							if (newRoot.getChild(1).getKind() == CAstNode.NEW) {
								left = newRoot.getChild(1).getChild(1);
							} else {
								left = newRoot.getChild(1);
							}
							
							CAstNode right;
							if (newRoot.getChild(2).getKind() == CAstNode.NEW) {
								right = newRoot.getChild(2).getChild(1);
							} else {
								right = newRoot.getChild(2);
							}
							
							if (left.getKind() == CAstNode.CONSTANT && right.getKind() == CAstNode.CONSTANT) {
								CAstNode result = Ast.makeConstant(left.getValue().equals(right.getValue()));
							    nodeMap.put(Pair.make(root, context.key()), result);
							    return result;
							}
						}
					} else if (newRoot.getKind() == CAstNode.CALL && 
							   newRoot.getChildCount() > 2 &&
							   newRoot.getChild(0).getKind() == CAstNode.TYPE_LITERAL_EXPR &&
							   newRoot.getChild(2).getKind() == CAstNode.CONSTANT) {
						CAstType rvct = fun.getNodeTypeMap().getNodeType(root.getChild(2));
						TypeReference rvtr = SolidityCAstType.getIRType(rvct);
						IClassHierarchy cha = getClassHierarchy();
						IClass rvc = cha.lookupClass(rvtr);

						if (rvc != null && cha.isAssignableFrom(cha.lookupClass(SolidityTypes.enm), rvc)) { 
							int idx;
							if ((idx = ((EnumType)rvct).getMembers().indexOf(newRoot.getChild(2).getValue())) >= 0) {
								CAstNode result = Ast.makeConstant(idx);
							    nodeMap.put(Pair.make(root, context.key()), result);
							    return result;		
							}
						}
					}
					
					return newRoot;
				}


				@Override
				protected Object eval(CAstOperator op, Object lhs, Object rhs) {
					// TODO Auto-generated method stub
					return null;
				}
				
			};
			
			@Override
			public IR makeIR(IMethod method, final Context context, final SSAOptions options) {
				  if (shouldRetranslate(method, context)) {
					  SolidityLoader loader = (SolidityLoader)loaders.getTheLoader();
					  loader.startRetranslating();
					  
					  AstTranslator xlator = new SolidityAstTranslator(loader) {
						  @Override
						  public void translate(CAstEntity N, final WalkContext walkContext) {
							  N = new SolidityEnumConstantRewriter(N, context, new CAstImpl()).rewrite(N);
							  super.translate(N, walkContext);
						  }
					  };

					  ((Retranslatable)method).retranslate(xlator);
					  
					  Map<TypeName, IClass> x = loader.doneRetranslating();
					  method = x.get(method.getDeclaringClass().getName()).getMethod(method.getSelector());
				  }

				  
				  return super.makeIR(method, context, options);
			  }

	    }, makeOptions().getSSAOptions());
	  }

	@Override
	protected Iterable<Entrypoint> makeDefaultEntrypoints(IClassHierarchy cha) {
		return LinkedEntrypoint.getContractEntrypoints(spec==null? conf: spec, cha);
	}

	@Override
	public AnalysisOptions getDefaultOptions(Iterable<Entrypoint> entrypoints) {
		AnalysisOptions options = makeOptions();
		options.setEntrypoints(entrypoints);
		return options;
	}

	@Override
	protected CallGraphBuilder<InstanceKey> buildCallGraph(IClassHierarchy cha, AnalysisOptions options,
			boolean savePointerAnalysis, IProgressMonitor monitor) throws IllegalArgumentException, CancelException {
		long start = System.nanoTime();
		try {
			return super.buildCallGraph(cha, options, savePointerAnalysis, monitor);
		} finally {
			statistics.put("build_cg", System.nanoTime() - start);			
		}
	}

	@Override
	public PropagationCallGraphBuilder defaultCallGraphBuilder() throws IllegalArgumentException, IOException {
		return getCallGraphBuilder(
				getClassHierarchy(), 
				getDefaultOptions(makeDefaultEntrypoints(getClassHierarchy())),
				makeDefaultCache());
	}

}

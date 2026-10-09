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
package com.certora.wala.cast.solidity.translator;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.certora.wala.cast.solidity.loader.ContractType;
import com.certora.wala.cast.solidity.loader.EnumType;
import com.certora.wala.cast.solidity.loader.FunctionType;
import com.certora.wala.cast.solidity.loader.SolidityLoader;
import com.certora.wala.cast.solidity.loader.StructType;
import com.certora.wala.cast.solidity.tree.SolidityArrayType;
import com.certora.wala.cast.solidity.tree.SolidityCAstType;
import com.certora.wala.cast.solidity.tree.SolidityMappingType;
import com.certora.wala.cast.solidity.tree.SolidityTupleType;
import com.certora.wala.cast.solidity.types.SolidityTypes;
import com.ibm.wala.cast.ir.ssa.AstInstructionFactory;
import com.ibm.wala.cast.ir.translator.AstTranslator;
import com.ibm.wala.cast.loader.AstMethod.DebuggingInformation;
import com.ibm.wala.cast.tree.CAstEntity;
import com.ibm.wala.cast.tree.CAstNode;
import com.ibm.wala.cast.tree.CAstNodeTypeMap;
import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.cast.tree.CAstType;
import com.ibm.wala.cast.tree.impl.CAstSymbolImpl;
import com.ibm.wala.cast.tree.visit.CAstVisitor;
import com.ibm.wala.cast.types.AstMethodReference;
import com.ibm.wala.cfg.AbstractCFG;
import com.ibm.wala.cfg.IBasicBlock;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IClassLoader;
import com.ibm.wala.classLoader.ModuleEntry;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.core.util.strings.Atom;
import com.ibm.wala.shrike.shrikeBT.IInvokeInstruction.Dispatch;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;

public class SolidityAstTranslator extends AstTranslator {
	private final AstInstructionFactory insts; 
	
	public SolidityAstTranslator(IClassLoader loader) {
		super(loader);
		this.insts = (AstInstructionFactory) loader.getLanguage().instructionFactory();
	}

	@Override
	protected String composeEntityName(WalkContext parent, CAstEntity f) {
		if (parent.top().getKind() == CAstEntity.FILE_ENTITY) {
			return f.getName();
		} else {
			if (f.getKind() == CAstEntity.FUNCTION_ENTITY &&  ((FunctionType)f.getType()).getDeclaringType() != null) {
				if (f.getType() instanceof FunctionType) {
					return ((FunctionType)f.getType()).getDeclaringType().getName() + "." + f.getType().getName();
				} else {
					return f.getType().getName();
				}
			} else {
				String myName = parent.top().getName();
				return (myName.contains("/")? myName.substring(myName.lastIndexOf('/')+1): myName) + "/" + f.getName();
			}
		}
	}

	@Override
	protected void doPrologue(WalkContext context) {
		context.currentScope().getConstantValue(0);
		
		int v = context.currentScope().allocateTempValue();
		context.cfg().addInstruction(insts.NewInstruction(context.cfg().getCurrentInstruction(), v, NewSiteReference.make(context.cfg().getCurrentInstruction(), SolidityTypes.msg)));
		context.currentScope().declare(new CAstSymbolImpl("msg", CAstType.DYNAMIC), v);

		v = context.currentScope().allocateTempValue();
		context.cfg().addInstruction(insts.LoadMetadataInstruction(context.cfg().getCurrentInstruction(), v, SolidityTypes.codeBody, MethodReference.findOrCreate(SolidityTypes.root, "getType", "(Lroot;)LCodeBody;")));
		context.currentScope().declare(new CAstSymbolImpl("type", CAstType.DYNAMIC), v);

		v = context.currentScope().allocateTempValue();
		context.cfg().addInstruction(insts.NewInstruction(context.cfg().getCurrentInstruction(), v, NewSiteReference.make(context.cfg().getCurrentInstruction(), SolidityTypes.abi)));
		context.currentScope().declare(new CAstSymbolImpl("abi", CAstType.DYNAMIC), v);

		v = context.currentScope().allocateTempValue();
		context.cfg().addInstruction(insts.NewInstruction(context.cfg().getCurrentInstruction(), v, NewSiteReference.make(context.cfg().getCurrentInstruction(), SolidityTypes.abi)));
		context.currentScope().declare(new CAstSymbolImpl("mulmod", CAstType.DYNAMIC), v);

		v = context.currentScope().allocateTempValue();
		context.cfg().addInstruction(insts.NewInstruction(context.cfg().getCurrentInstruction(), v, NewSiteReference.make(context.cfg().getCurrentInstruction(), SolidityTypes.block)));
		context.currentScope().declare(new CAstSymbolImpl("block", CAstType.DYNAMIC), v);

		v = context.currentScope().allocateTempValue();
		context.cfg().addInstruction(insts.NewInstruction(context.cfg().getCurrentInstruction(), v, NewSiteReference.make(context.cfg().getCurrentInstruction(), SolidityTypes.function)));
		context.currentScope().declare(new CAstSymbolImpl("require", CAstType.DYNAMIC), v);

		v = context.currentScope().allocateTempValue();
		context.cfg().addInstruction(insts.NewInstruction(context.cfg().getCurrentInstruction(), v, NewSiteReference.make(context.cfg().getCurrentInstruction(), SolidityTypes.function)));
		context.currentScope().declare(new CAstSymbolImpl("keccak256", CAstType.DYNAMIC), v);

		v = context.currentScope().allocateTempValue();
		context.cfg().addInstruction(insts.NewInstruction(context.cfg().getCurrentInstruction(), v, NewSiteReference.make(context.cfg().getCurrentInstruction(), SolidityTypes.function)));
		context.currentScope().declare(new CAstSymbolImpl("ecrecover", CAstType.DYNAMIC), v);

		v = context.currentScope().allocateTempValue();
		context.cfg().addInstruction(insts.NewInstruction(context.cfg().getCurrentInstruction(), v, NewSiteReference.make(context.cfg().getCurrentInstruction(), SolidityTypes.function)));
		context.currentScope().declare(new CAstSymbolImpl("revert", CAstType.DYNAMIC), v);

		v = context.currentScope().allocateTempValue();
		context.cfg().addInstruction(insts.NewInstruction(context.cfg().getCurrentInstruction(), v, NewSiteReference.make(context.cfg().getCurrentInstruction(), SolidityTypes.function)));
		context.currentScope().declare(new CAstSymbolImpl("assert", CAstType.DYNAMIC), v);

		v = context.currentScope().allocateTempValue();
		context.cfg().addInstruction(insts.NewInstruction(context.cfg().getCurrentInstruction(), v, NewSiteReference.make(context.cfg().getCurrentInstruction(), SolidityTypes.msg)));
		context.currentScope().declare(new CAstSymbolImpl("tx", CAstType.DYNAMIC), v);

		v = context.currentScope().allocateTempValue();
		context.cfg().addInstruction(insts.NewInstruction(context.cfg().getCurrentInstruction(), v, NewSiteReference.make(context.cfg().getCurrentInstruction(), SolidityTypes.function)));
		context.currentScope().declare(new CAstSymbolImpl("blockhash", CAstType.DYNAMIC), v);

		v = context.currentScope().allocateTempValue();
		context.cfg().addInstruction(insts.NewInstruction(context.cfg().getCurrentInstruction(), v, NewSiteReference.make(context.cfg().getCurrentInstruction(), SolidityTypes.function)));
		context.currentScope().declare(new CAstSymbolImpl("sha256", CAstType.DYNAMIC), v);

		v = context.currentScope().allocateTempValue();
		context.cfg().addInstruction(insts.NewInstruction(context.cfg().getCurrentInstruction(), v, NewSiteReference.make(context.cfg().getCurrentInstruction(), SolidityTypes.function)));
		context.currentScope().declare(new CAstSymbolImpl("gasleft", CAstType.DYNAMIC), v);

	}

	@Override
	protected void declareFunction(CAstEntity N, WalkContext context) {
		assert N.getKind() == CAstEntity.FUNCTION_ENTITY;
		((SolidityLoader)loader).defineFunctionType(N, composeEntityName(context, N), context);
	}

	@Override
	protected TypeReference defaultCatchType() {
		return SolidityTypes.root;
	}

	@Override
	protected void defineField(CAstEntity topEntity, WalkContext context, CAstEntity fieldEntity) {
		// noop, handled by defineType
	}

	@Override
	protected void defineFunction(CAstEntity N, WalkContext definingContext,
			AbstractCFG<SSAInstruction, ? extends IBasicBlock<SSAInstruction>> cfg, SymbolTable symtab,
			boolean hasCatchBlock, Map<IBasicBlock<SSAInstruction>, Set<TypeReference>> catchTypes,
			boolean hasMonitorOp, AstLexicalInformation lexicalInfo, DebuggingInformation debugInfo) {
		String clsName = composeEntityName(definingContext, N);
		((SolidityLoader)loader).defineFunctionBody(clsName, N, definingContext, cfg, symtab, hasCatchBlock, catchTypes, hasMonitorOp, lexicalInfo, debugInfo);
	}

	@Override
	protected boolean defineType(CAstEntity type, WalkContext wc) {
		String typeNameStr = composeEntityName(wc, type);
		TypeName typeName = TypeName.findOrCreate("L" + typeNameStr);
		IClass cls;
		if (! type.getType().getSupertypes().isEmpty()) {
			Set<TypeName> supers = type.getType().getSupertypes().stream().map(ct -> TypeName.findOrCreate("L" + ct.getName())).collect(Collectors.toSet());
			cls = ((SolidityLoader)loader).defineType(type, typeName, supers);
		} else {
			cls = ((SolidityLoader)loader).defineType(type, typeName, Collections.emptySet());
		}
		return true;
	}

	@Override
	public void doArrayRead(WalkContext context, int result, int arrayValue, CAstNode arrayRef, int[] dimValues) {
		CAstType t = context.top().getNodeTypeMap().getNodeType(arrayRef.getChild(0));
		TypeReference eltType;
		if (t instanceof SolidityMappingType) {
			CAstType eltCAstType = ((SolidityMappingType)t).getReturnType();
			eltType =  SolidityCAstType.getIRType(eltCAstType);
		} else if (t instanceof SolidityArrayType) {
			CAstType eltCAstType = ((SolidityArrayType)t).getElementType();
			eltType =  SolidityCAstType.getIRType(eltCAstType);			
		} else {
			eltType = SolidityTypes.bytes;
		}
		int instNum = context.cfg().getCurrentInstruction();
		context.cfg().addInstruction(insts.ArrayLoadInstruction(instNum, result, arrayValue, dimValues[0], eltType));
	
		Position[] operandPos =new Position[1 + dimValues.length];
		operandPos[0] = context.getSourceMap().getPosition(arrayRef.getChild(0));
		for(int i = 0; i < dimValues.length; i++) {
			operandPos[i+1] = context.getSourceMap().getPosition(arrayRef.getChild(i+2));			
		}
		context.cfg().noteOperands(instNum, operandPos);
	}

	@Override
	public void doArrayWrite(WalkContext context, int arrayValue, CAstNode arrayRef, CAstNode rvalNode, int[] dimValues, int rval) {
		CAstType t = context.top().getNodeTypeMap().getNodeType(arrayRef.getChild(0));
		TypeReference eltType;
		if (t instanceof SolidityMappingType) {
			CAstType eltCAstType = ((SolidityMappingType)t).getReturnType();
			eltType =  SolidityCAstType.getIRType(eltCAstType);
		} else if (t instanceof SolidityArrayType) {
			CAstType eltCAstType = ((SolidityArrayType)t).getElementType();
			eltType =  SolidityCAstType.getIRType(eltCAstType);			
		} else {
			eltType = SolidityTypes.bytes;
		}
		int instNum = context.cfg().getCurrentInstruction();
		context.cfg().addInstruction(insts.ArrayStoreInstruction(instNum, arrayValue, dimValues[0], rval, eltType));

		Position[] operandPos = new Position[2 + dimValues.length];
		operandPos[0] = context.getSourceMap().getPosition(arrayRef.getChild(0));
		operandPos[operandPos.length-1] = context.getSourceMap().getPosition(rvalNode);
		for(int i = 0; i < dimValues.length; i++) {
			operandPos[i+1] = context.getSourceMap().getPosition(arrayRef.getChild(i+2));			
		}
		context.cfg().noteOperands(instNum, operandPos);
	}
	
	@Override
	protected void doCall(WalkContext context, CAstNode call, int result, int exception, CAstNode name, int receiver,
			int[] arguments) {
		if (call.getChild(0).getKind() == CAstNode.TYPE_LITERAL_EXPR) {
			String typeName = (String) call.getChild(0).getChild(0).getValue();
			TypeReference type = SolidityCAstType.getIRType(SolidityCAstType.get(typeName));
			if (type != null) {
				context.cfg().addInstruction(insts.CheckCastInstruction(context.cfg().getCurrentInstruction(), result, arguments[0], type, true));
			} else {
				context.cfg().addInstruction(insts.AssignInstruction(context.cfg().getCurrentInstruction(), result, arguments[0]));
			}
		} else if (call.getChild(0).getKind() == CAstNode.PRIMITIVE &&
				"type".equals(call.getChild(0).getChild(0).getValue()) &&
				call.getChild(2).getKind() == CAstNode.TYPE_LITERAL_EXPR) {
			context.cfg().addInstruction(insts.LoadMetadataInstruction(context.cfg().getCurrentInstruction(), result, SolidityTypes.root, TypeReference.findOrCreate(SolidityTypes.solidity, (String)call.getChild(2).getChild(0).getValue())));
		} else if (call.getChild(0).getKind() == CAstNode.PRIMITIVE &&
				"revert".equals(call.getChild(0).getChild(0).getValue())) {
			context.cfg().addInstruction(insts.ThrowInstruction(context.cfg().getCurrentInstruction(), arguments.length>0? arguments[0]: context.currentScope().getConstantValue(null)));
			context.cfg().addPreEdgeToExit(context.cfg().getCurrentBlock(), true);
		} else if (call.getChild(0).getKind() == CAstNode.OBJECT_REF &&
				   call.getChild(0).getChild(0).getKind() == CAstNode.TYPE_LITERAL_EXPR &&
				   "uint256".equals(call.getChild(0).getChild(0).getChild(0).getValue()) &&
				   ("wrap".equals(call.getChild(0).getChild(1).getValue()) || 
					"unwrap".equals(call.getChild(0).getChild(1).getValue()))) {
			context.cfg().addInstruction(insts.AssignInstruction(context.cfg().getCurrentInstruction(), result, arguments[0]));			
		} else {
			int argsAndSelf[] = new int[ arguments.length + 1 ];
			argsAndSelf[0] = receiver;
			System.arraycopy(arguments, 0, argsAndSelf, 1, arguments.length);

			CAstType recCAstType = context.top().getNodeTypeMap().getNodeType(call.getChild(0));
			MethodReference m;
			if (! (recCAstType instanceof FunctionType)) {
				TypeReference retType = SolidityCAstType.getIRType(recCAstType==null? context.top().getNodeTypeMap().getNodeType(call): recCAstType);
				TypeName[] argTypes = new TypeName[ arguments.length ];
				for(int i = 0; i < argTypes.length; i++) {
					argTypes[i] = SolidityTypes.root.getName();
				}
				Descriptor d = Descriptor.findOrCreate(argTypes, retType.getName());
				m = MethodReference.findOrCreate(SolidityTypes.root, AstMethodReference.fnAtom, d);
			} else {
				m = ((SolidityLoader)loader).getReference((FunctionType) recCAstType);
			}
			
			// A super call's callee is the member access super.<member>, whose base the
			// CAst builder renders as the magic PRIMITIVE "super". A callee that merely
			// CONTAINS a super call (super.f(x).half()) has a CALL base and dispatches
			// normally.
			CAstNode callee = call.getChild(0);
			boolean superCall = callee.getKind() == CAstNode.OBJECT_REF
					&& callee.getChild(0).getKind() == CAstNode.PRIMITIVE
					&& callee.getChild(0).getChildCount() > 0
					&& "super".equals(callee.getChild(0).getChild(0).getValue());
			if (Boolean.getBoolean("debugSuperDispatch")) {
				boolean oldHeuristic = false;
				String text = "<no position>";
				try {
					com.ibm.wala.cast.tree.CAstSourcePositionMap.Position p = context.top().getSourceMap().getPosition(callee);
					if (p != null) {
						text = new com.ibm.wala.cast.util.SourceBuffer(p).toString();
						oldHeuristic = text.startsWith("super.");
					}
				} catch (java.io.IOException e) {
					text = "<unreadable>";
				}
				if (oldHeuristic != superCall) {
					System.err.println("[superDispatch] structural=" + superCall + " old=" + oldHeuristic
							+ " callee=" + callee + " text='" + text.replace('\n', ' ') + "'");
				}
			}

			// An explicit base-contract call Base.f(...) is bound statically to Base's f: Solidity
			// does no virtual dispatch on it, so the most derived override must not be chosen.
			boolean baseCall = callee.getKind() == CAstNode.OBJECT_REF && isBaseContractRef(context, callee.getChild(0));

			int instNum = context.cfg().getCurrentInstruction();
			CallSiteReference csr = baseCall? new BaseCallSiteReference(instNum, m)
					: CallSiteReference.make(instNum, m, superCall? Dispatch.SPECIAL: Dispatch.VIRTUAL);

			Position[] operandPos;
			if (m.getNumberOfParameters() == argsAndSelf.length && call.getChild(0).getKind() == CAstNode.OBJECT_REF) {
				int[] newArgs = new int[ argsAndSelf.length + 1 ];
				newArgs[0] = receiver;
				newArgs[1] = context.getValue(call.getChild(0).getChild(0));
				if (arguments.length > 0) {
					System.arraycopy(arguments, 0, newArgs, 2, arguments.length);
				}
				argsAndSelf = newArgs;

				operandPos = new Position[ arguments.length + 2 ];
				operandPos[1] = context.getSourceMap().getPosition(call.getChild(0).getChild(0));
				for(int i = 2; i < operandPos.length; i++) {
					operandPos[i] = context.getSourceMap().getPosition(call.getChild(i));
				}

			} else {
				operandPos = new Position[ arguments.length + 1 ];
				for(int i = 1; i < operandPos.length; i++) {
					operandPos[i] = context.getSourceMap().getPosition(call.getChild(i+1));
				}
			}
			operandPos[0] = context.getSourceMap().getPosition(call.getChild(0));
			
			context.cfg().addInstruction(insts.InvokeInstruction(instNum, m.getReturnType() == TypeReference.Void? -1: result, argsAndSelf, context.currentScope().allocateTempValue(), csr, null));			
		
			context.cfg().noteOperands(instNum, operandPos);
		}
	}

	/** The name of a contract (not a library or interface) used as the base of a member access, as in {@code Base.f}. */
	private static boolean isBaseContractRef(WalkContext context, CAstNode n) {
		return n.getKind() == CAstNode.TYPE_LITERAL_EXPR
				&& context.top().getNodeTypeMap().getNodeType(n) instanceof ContractType;
	}

	@Override
	protected void doFieldRead(WalkContext context, int result, int receiver, CAstNode elt, CAstNode parent) {
		CAstEntity code = context.top();
		CAstNodeTypeMap typeMap = code.getNodeTypeMap();
		CAstType objCAstType = typeMap.getNodeType(parent.getChild(0));
		CAstType eltCAstType = typeMap.getNodeType(parent);	
		TypeReference objType = objCAstType==null? SolidityTypes.root: SolidityCAstType.getIRType(objCAstType);
		TypeReference eltType = eltCAstType==null? SolidityTypes.root: SolidityCAstType.getIRType(eltCAstType);
		if (eltCAstType instanceof FunctionType) {
			TypeReference t = TypeReference.findOrCreate(SolidityTypes.solidity, eltType.getName());
			NewSiteReference ns = NewSiteReference.make(context.cfg().getCurrentInstruction(), t);
			context.cfg().addInstruction(insts.NewInstruction(ns.getProgramCounter(), result, ns));
			FieldReference self = FieldReference.findOrCreate(SolidityTypes.function, Atom.findOrCreateUnicodeAtom("self"), SolidityTypes.root);
			if (isBaseContractRef(context, parent.getChild(0))) {
				// Base.f runs on this contract's own state, like super.f
				receiver = context.currentScope().allocateTempValue();
				context.cfg().addInstruction(insts.GetInstruction(context.cfg().getCurrentInstruction(), receiver, 1, self));
			}
			context.cfg().addInstruction(insts.PutInstruction(context.cfg().getCurrentInstruction(), result, receiver, self));
		} else {
			int instNum = context.cfg().getCurrentInstruction();
			String fieldName = elt.getValue().toString();
			context.cfg().addInstruction(insts.GetInstruction(instNum, result, receiver, FieldReference.findOrCreate("self".equals(fieldName)? SolidityTypes.function: objType, Atom.findOrCreateUnicodeAtom(fieldName), eltType)));		
			Position[] operandPos = new Position[2];
			operandPos[0] = context.getSourceMap().getPosition(parent.getChild(0));
			operandPos[1] = context.getSourceMap().getPosition(elt);
			context.cfg().noteOperands(instNum, operandPos);

		}
	}

	@Override
	protected void doFieldWrite(WalkContext context, int receiver, CAstNode elt, CAstNode parent, CAstNode rvalNode, int rval) {
		CAstEntity code = context.top();
		CAstNodeTypeMap typeMap = code.getNodeTypeMap();
		CAstType objCAstType = typeMap.getNodeType(parent.getChild(0));
		CAstType eltCAstType = typeMap.getNodeType(parent);	
		TypeReference objType = objCAstType==null? SolidityTypes.root: SolidityCAstType.getIRType(objCAstType);
		TypeReference eltType = eltCAstType==null? SolidityTypes.root: SolidityCAstType.getIRType(eltCAstType);
		int instNum = context.cfg().getCurrentInstruction();
		context.cfg().addInstruction(insts.PutInstruction(instNum, receiver, rval, FieldReference.findOrCreate(objType, Atom.findOrCreateUnicodeAtom((String)elt.getValue()), eltType)));

		Position[] operandPos = new Position[3];
		operandPos[0] = context.getSourceMap().getPosition(parent.getChild(0));
		operandPos[1] = context.getSourceMap().getPosition(elt);
		operandPos[2] = context.getSourceMap().getPosition(rvalNode);
		context.cfg().noteOperands(instNum, operandPos);
	}
	
	@Override
	protected void doNewObject(WalkContext context, CAstNode newNode, int result, Object type, int[] arguments) {
		if (newNode.getChildCount() >= 1 && newNode.getChild(0).getValue() instanceof SolidityTupleType) {
			SolidityTupleType tt = (SolidityTupleType) newNode.getChild(0).getValue(); 
			context.cfg().addInstruction(insts.NewInstruction(context.cfg().getCurrentInstruction(), result, NewSiteReference.make(context.cfg().getCurrentInstruction(), SolidityTypes.tuple)));
			for(int i = 1; i < newNode.getChildCount(); i++) {
				TypeReference t =SolidityCAstType.getIRType(tt.getElement(i-1));
				context.cfg().addInstruction(insts.PutInstruction(context.cfg().getCurrentInstruction(), result, context.getValue(newNode.getChild(i)), FieldReference.findOrCreate(SolidityTypes.tuple, Atom.findOrCreateUnicodeAtom(""+(i-1)), t)));
			}
		} else if (newNode.getChildCount() >= 1 && newNode.getChild(0).getValue() instanceof StructType) {
			// S(a, b): a fresh struct, then each member's value written to its field
			TypeReference st = SolidityCAstType.getIRType((StructType) newNode.getChild(0).getValue());
			context.cfg().addInstruction(insts.NewInstruction(context.cfg().getCurrentInstruction(), result, NewSiteReference.make(context.cfg().getCurrentInstruction(), st)));
			for(int i = 1; i + 2 < newNode.getChildCount(); i += 3) {
				String member = (String) newNode.getChild(i).getValue();
				TypeReference t = SolidityCAstType.getIRType((CAstType) newNode.getChild(i+1).getValue());
				context.cfg().addInstruction(insts.PutInstruction(context.cfg().getCurrentInstruction(), result, context.getValue(newNode.getChild(i+2)), FieldReference.findOrCreate(st, Atom.findOrCreateUnicodeAtom(member), t)));
			}
		} else if (newNode.getChildCount() == 2 && newNode.getChild(0).getValue() instanceof EnumType) {
			TypeReference et = SolidityCAstType.getIRType((EnumType)newNode.getChild(0).getValue());
			int evn = context.currentScope().getConstantValue(newNode.getChild(1).getValue());
			context.cfg().addInstruction(insts.NewInstruction(context.cfg().getCurrentInstruction(), result, NewSiteReference.make(context.cfg().getCurrentInstruction(), et), new int[] { evn }));

		} else {

		}
	}

	@Override
	protected void doPrimitive(int resultVal, WalkContext context, CAstNode primitiveCall) {
		String name = (String)primitiveCall.getChild(0).getValue();
		if ("delete".equals(name)) {
			context.cfg().addInstruction(
					insts.AssignInstruction(context.cfg().getCurrentInstruction(), 
						resultVal, context.currentScope().getConstantValue(null)));
		} else if ("this".equals(name) || "super".equals(name)) {		
			FieldReference self = FieldReference.findOrCreate(SolidityTypes.function, Atom.findOrCreateUnicodeAtom("self"), SolidityTypes.root);

			context.cfg().addInstruction(
				insts.GetInstruction(context.cfg().getCurrentInstruction(), resultVal, 1, self));
			
		} else {
			context.cfg().addInstruction(
				insts.AssignInstruction(context.cfg().getCurrentInstruction(), 
					resultVal, 
					context.currentScope().lookup(name).valueNumber()));
		}
	}

	@Override
	protected void doThrow(WalkContext context, int exception) {
		context.cfg().addPreEdgeToExit(context.cfg().getCurrentBlock(), true);
	}

	@Override
	protected void doMaterializeFunction(CAstNode node, WalkContext context, int result, int exception, CAstEntity fn) {
		assert false;
	}

	@Override
	protected CAstType exceptionType() {
		return SolidityCAstType.get("root");
	}

	@Override
	protected Position[] getParameterPositions(CAstEntity e) {
		int nargs = e.getArgumentCount();
		Position[] args = new Position[nargs];
		for(int i = 0; i < nargs; i++) {
			args[i] = e.getPosition(i);
		}
		return args;
	}

	@Override
	protected TypeReference makeType(CAstType type) {
		return SolidityCAstType.getIRType(type);
	}

	@Override
	protected CAstType topType() {
		return SolidityCAstType.get("root");
	}

	@Override
	protected boolean treatGlobalsAsLexicallyScoped() {
		return false;
	}

	@Override
	protected boolean useDefaultInitValues() {
		return false;
	}

	/**
	 * Destructuring {@code (x, , y) = rhs}. CAstVisitor's fallback passes the assignment node
	 * and the right-hand side in the opposite order to {@code visitAssignNodes}, and has
	 * already translated the right-hand side. Each variable gets its own component, read from
	 * the tuple after the whole right-hand side is evaluated, so {@code (x, y) = (y, x)} swaps.
	 */
	@Override
	protected boolean doVisitAssignNodes(CAstNode n, WalkContext context, CAstNode assign, CAstNode rhs,
			CAstVisitor<WalkContext> visitor) {
		if (n.getKind() == CAstNode.NEW && n.getChild(0).getValue() instanceof SolidityTupleType) {
			SolidityTupleType t = (SolidityTupleType) n.getChild(0).getValue();
			int rval = context.getValue(rhs);
			for(int i = 1; i < n.getChildCount(); i++) {
				if (n.getChild(i).getKind() == CAstNode.VAR) {
					TypeReference eltType = SolidityCAstType.getIRType(t.getElement(i-1));
					int component = context.currentScope().allocateTempValue();
					context.cfg().addInstruction(insts.GetInstruction(context.cfg().getCurrentInstruction(), component, rval,
							FieldReference.findOrCreate(SolidityTypes.tuple, Atom.findOrCreateUnicodeAtom(""+(i-1)), eltType)));
					doLocalWrite(context, (String)n.getChild(i).getChild(0).getValue(), eltType, component);
				}
			}
			
			return true;
		} else {
			return super.doVisitAssignNodes(n, context, assign, rhs, visitor);
		}
	}

	  public void translate(final CAstEntity N, final ModuleEntry module) {
		  super.translate(N, module);
	  }
}

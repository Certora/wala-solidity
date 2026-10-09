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
package com.certora.wala.cast.solidity.json;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import com.certora.wala.cast.solidity.loader.ContractType;
import com.certora.wala.cast.solidity.loader.EnumType;
import com.certora.wala.cast.solidity.loader.FunctionType;
import com.certora.wala.cast.solidity.loader.InterfaceType;
import com.certora.wala.cast.solidity.loader.LibraryType;
import com.certora.wala.cast.solidity.loader.SolidityJSONLoader;
import com.certora.wala.cast.solidity.loader.StructType;
import com.certora.wala.cast.solidity.tree.CallableEntity;
import com.certora.wala.cast.solidity.tree.ContractEntity;
import com.certora.wala.cast.solidity.tree.EnumEntity;
import com.certora.wala.cast.solidity.tree.EventEntity;
import com.certora.wala.cast.solidity.tree.FunctionEntity;
import com.certora.wala.cast.solidity.tree.SolidityArrayType;
import com.certora.wala.cast.solidity.tree.SolidityCAstType;
import com.certora.wala.cast.solidity.tree.SolidityMappingType;
import com.certora.wala.cast.solidity.tree.SolidityTupleType;
import com.google.common.collect.Streams;
import com.ibm.wala.cast.ir.translator.AbstractEntity;
import com.ibm.wala.cast.ir.translator.AbstractFieldEntity;
import com.ibm.wala.cast.ir.translator.TranslatorToCAst;
import com.ibm.wala.cast.ir.translator.TranslatorToCAst.Error;
import com.ibm.wala.cast.tree.CAst;
import com.ibm.wala.cast.tree.CAstControlFlowMap;
import com.ibm.wala.cast.tree.CAstEntity;
import com.ibm.wala.cast.tree.CAstNode;
import com.ibm.wala.cast.tree.CAstQualifier;
import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.cast.tree.CAstSymbol;
import com.ibm.wala.cast.tree.CAstType;
import com.ibm.wala.cast.tree.impl.CAstControlFlowRecorder;
import com.ibm.wala.cast.tree.impl.CAstImpl;
import com.ibm.wala.cast.tree.impl.CAstNodeTypeMapRecorder;
import com.ibm.wala.cast.tree.impl.CAstOperator;
import com.ibm.wala.cast.tree.impl.CAstSourcePositionRecorder;
import com.ibm.wala.cast.tree.impl.CAstSymbolImpl;
import com.ibm.wala.cast.tree.rewrite.CAstRewriter.CopyKey;
import com.ibm.wala.cast.tree.rewrite.CAstRewriter.RewriteContext;
import com.ibm.wala.cast.tree.rewrite.CAstRewriterFactory;
import com.ibm.wala.classLoader.IMethod.SourcePosition;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetUtil;
import com.ibm.wala.util.intset.MutableIntSet;

public class JSONToCAst {
	private int idx = 0;

	/**
	 * Where the {@code return}s of the function being translated jump to. Every value-returning
	 * function gets one exit: a {@code return e} assigns the return variables and jumps there, and
	 * the single {@code return} of those variables sits at the exit. Early returns then merge like
	 * any other branch, so analyses see one phi per returned value instead of several exits.
	 */
	private static final class ReturnTarget {
		final List<String> names;
		/** The tuple of the return variables' types, for destructuring a returned tuple into them. */
		final CAstType type;
		final JSONObject exit;

		ReturnTarget(List<String> names, CAstType type, JSONObject exit) {
			this.names = names;
			this.type = type;
			this.exit = exit;
		}
	}

	private final Deque<ReturnTarget> returnTargets = new ArrayDeque<>();

	/** The multiplier each Solidity unit suffix applies to a number literal. */
	private static final Map<String, BigInteger> SUBDENOMINATIONS = Map.ofEntries(
		Map.entry("wei", BigInteger.ONE), Map.entry("gwei", BigInteger.TEN.pow(9)),
		Map.entry("szabo", BigInteger.TEN.pow(12)), Map.entry("finney", BigInteger.TEN.pow(15)),
		Map.entry("ether", BigInteger.TEN.pow(18)),
		Map.entry("seconds", BigInteger.ONE), Map.entry("minutes", BigInteger.valueOf(60)),
		Map.entry("hours", BigInteger.valueOf(3600)), Map.entry("days", BigInteger.valueOf(86400)),
		Map.entry("weeks", BigInteger.valueOf(604800)), Map.entry("years", BigInteger.valueOf(31536000)));

	private final CAst ast = new CAstImpl();
	private final Map<Object, CAstType> entityTypes = HashMapFactory.make();
	private final Map<CAstType, Set<String>> supers = HashMapFactory.make();

	private final SolidityJSONLoader loader;

	public JSONToCAst(SolidityJSONLoader loader) {
		this.loader = loader;
	}
	
	public class SolidityJSONTranslator implements TranslatorToCAst {

		private class TranslationVisitor implements JsonNodeTypeVisitor<CAstNode, SolidityWalkContext> {

			public Class<SolidityWalkContext> context() {
				return SolidityWalkContext.class;
			}

			abstract class FunctionKey {
				abstract String name();
				abstract CAstType self();
				abstract CAstType[] args();
				abstract CAstType[] rets();
				
				public int hashCode() {
					return name().hashCode() *
						(args()==null? 1: Arrays.hashCode(args())) *
						(rets()==null? 1: Arrays.hashCode(rets())) *
						(self()==null? 1: self().hashCode());
				}
				
				public boolean equals(Object o) {
					if (o.getClass() == getClass()) {
						FunctionKey k = (FunctionKey) o;
						if (! name().equals(k.name())) {
							return false;
						}
						if (self()==null? k.self()!=null: !self().equals(k.self())) {
							return false;
						}
						if (args()==null? k.args()!=null: !Arrays.equals(args(), k.args()) ) {
							return false;
						}
						if (rets()==null? k.rets()!=null: !Arrays.equals(rets(), k.rets()) ) {
							return false;
						}
						
						return true;
					} else {
						return false;
					}
				}
			}
			
			private FunctionKey makeFunctionKey(JSONObject functionDefinition, SolidityWalkContext context) {
				String name = functionDefinition.has("kind") && functionDefinition.getString("kind").equals("constructor")? "<init>": functionDefinition.getString("name");
				
				CAstType selfType = null;
				if (functionDefinition.has("scope") && !"freeFunction".equals(functionDefinition.getString("kind"))) {
					JSONObject contract = getDeclaration(functionDefinition.getInt("scope"), null, context);
					if (contract != null && contract.has("contractKind")) {
						selfType = findOrCreateType(contract, context, this::newContractType);
					}
				}
				
			    CAstType[] parameters = functionDefinition.has("parameters")?
			    		getTypes(functionDefinition.getJSONObject("parameters").getJSONArray("parameters"), context):
			    		null;
			    CAstType[] returnParameters = functionDefinition.has("returnParameters")?
			    		getTypes(functionDefinition.getJSONObject("returnParameters").getJSONArray("parameters"), context):
			    		null;
			    
			    CAstType st = selfType;
				return new FunctionKey() {

					@Override
					String name() {
						return name;
					}

					@Override
					CAstType self() {
						return st;
					}

					@Override
					CAstType[] args() {
						return parameters;
					}

					@Override
					CAstType[] rets() {
						return returnParameters;
					}
					
				};
			}	
			private FunctionType newFunctionType(JSONObject functionDefinition, SolidityWalkContext context) {
				FunctionKey k = makeFunctionKey(functionDefinition, context);
			    return FunctionType.findOrCreate(k.name(), k.self(), k.rets(), k.args());
			}

			private StructType newStructType(JSONObject structDefinition, SolidityWalkContext context) {
				return new StructType("struct " + structDefinition.getString("canonicalName"));
			}

			private EnumType newEnumType(JSONObject enumDefinition, SolidityWalkContext context) {
				String enumName = enumDefinition.getString("canonicalName");

				List<String> members = Streams.stream(enumDefinition.getJSONArray("members").iterator())
						.map(e -> ((JSONObject) e).getString("name")).toList();

				return new EnumType(enumName, members);
			}
			
			private CAstType.Class newContractType(JSONObject contractDefinition, SolidityWalkContext context) {
				String kind = contractDefinition.getString("contractKind");
				Set<String> superTypes = HashSetFactory.make();
				String name = "contract " + (contractDefinition.has("canonicalName")? contractDefinition.getString("canonicalName"): contractDefinition.getString("name"));
				switch (kind) {
				case "interface":
					 CAstType.Class t = new InterfaceType(name, superTypes);
					 supers.put(t, superTypes);
					 return t;
				case "library":
					return new LibraryType(name);
				case "contract":
					Set<CAstQualifier> quals = HashSetFactory.make();
					if (contractDefinition.has("abstract") && contractDefinition.getBoolean("abstract")) {
						quals.add(CAstQualifier.ABSTRACT);
					}
					 t = new ContractType(name, superTypes, quals);
					 supers.put(t, superTypes);
					 return t;
				default:
					assert false : contractDefinition;
					return null;
				}
			}

			private CAstType getType(JSONObject node, SolidityWalkContext context) {
				CAstType ret = new JsonNodeTypeOnlyVisitor<CAstType>() {
					@SuppressWarnings("unused")
					public CAstType visitContractDefinition(JSONObject o, Void ignore) {
						return findOrCreateType(o, context, TranslationVisitor.this::newContractType);
					}
					@SuppressWarnings("unused")
					public CAstType visitEnumDefinition(JSONObject o, Void ignore) {
						return findOrCreateType(o, context, TranslationVisitor.this::newEnumType);
					}
					
					@SuppressWarnings("unused")
					public CAstType visitStructDefinition(JSONObject o, Void ignore) {
						return findOrCreateType(o, context, TranslationVisitor.this::newStructType);
					}
					
					@SuppressWarnings("unused")
					public CAstType visitFunctionDefinition(JSONObject o, Void ignore) {
						return findOrCreateType(o, context, TranslationVisitor.this::newFunctionType);
					}
					
					@SuppressWarnings("unused")
					public CAstType visitArrayTypeName(JSONObject o, Void ignore) {
						return SolidityArrayType.get(getType(o.getJSONObject("baseType"), context));
					}

					@SuppressWarnings("unused")
					public CAstType visitMapping(JSONObject o, Void ignore) {
						return SolidityMappingType.get(getType(o.getJSONObject("keyType"), context),
								getType(o.getJSONObject("valueType"), context));
					}

					@SuppressWarnings("unused")
					public CAstType visitElementaryTypeName(JSONObject o, Void context) {
						return SolidityCAstType.get(o.getString("name"));
					}

					@SuppressWarnings("unused")
					public CAstType visitUserDefinedTypeName(JSONObject o, Void ignore) {
						return getType(getDeclaration(o, context), context);
					}

					@SuppressWarnings("unused")
					public CAstType visitUserDefinedValueTypeDefinition(JSONObject o, Void ignore) {
						return getType(o.getJSONObject("underlyingType"), context);
					}
					
					@Override
					public CAstType visitNode(JSONObject o, Void ignore) {
						JSONObject typeDesc = o.getJSONObject("typeDescriptions");
						if (typeDesc.has("typeString")) {
							CAstType ct = SolidityCAstType.get(typeDesc.getString("typeString"));
							if (ct != null) {
								return ct;
							}
						}
						return parseTypeIdentifier(typeDesc.getString("typeIdentifier"), context);
					}
				}.visit(node, null);
				return ret;
			}
			
			private Pair<CAstType,String> parseNextTypeIdentifier(String typeId, SolidityWalkContext context) {
				if (typeId.startsWith("t_mapping$_")) {
					Pair<CAstType, String> keyType = parseNextTypeIdentifier(typeId.substring(11), context);
					String rest = keyType.snd;
					if (rest.startsWith("_$_")) {
						Pair<CAstType, String> valueType = parseNextTypeIdentifier(rest.substring(3), context);
						String remaining = valueType.snd;
						if (remaining.startsWith("_$")) {
							return Pair.make(SolidityMappingType.get(keyType.fst, valueType.fst), remaining.substring(2));
						}
					}

				} else if (typeId.startsWith("t_array$_")) {
						Pair<CAstType, String> keyType = parseNextTypeIdentifier(typeId.substring(9), context);
						String remaining = keyType.snd;
						if (remaining.startsWith("_$")) {
							remaining = remaining.substring(2);

							while (remaining.matches("^[0-9]*_.*")) {
								remaining = remaining.substring(1);
							}

							if (remaining.startsWith("dyn_memory_ptr")) {
								remaining = remaining.substring(14);
							} else if (remaining.startsWith("memory_ptr")) {
								remaining = remaining.substring(10);
							} else if (remaining.startsWith("dyn_storage_ptr")) {
								remaining = remaining.substring(15);
							} else 	if (remaining.startsWith("dyn_calldata_ptr")) {
								remaining = remaining.substring(16);
							}

							return Pair.make(SolidityArrayType.get(keyType.fst), remaining);
						}
						

				} else if (typeId.startsWith("t_tuple$_")) {
					List<CAstType> elts = new ArrayList<>();
					String remainder = typeId.substring(9);
					while (! remainder.startsWith("_$")) {
						Pair<CAstType,String> next = parseNextTypeIdentifier(remainder, context);
						remainder = next.snd;
						elts.add(next.fst);
					}
					return Pair.make(SolidityTupleType.get(elts.toArray(new CAstType[elts.size()])), remainder.substring(2));

				} else if (typeId.startsWith("t_magic_meta_type_")) {
					Pair<CAstType, String> x = parseNextTypeIdentifier(typeId.substring(19), context);
					return x;
					
				} else if (typeId.startsWith("t_magic_")) {
					int endIndex = typeId.indexOf('_', 8);
					if (endIndex > 0) {
						String type = typeId.substring(8, endIndex);
						return Pair.make(SolidityCAstType.get(type), typeId.substring(endIndex));
					} else {
						return Pair.make(SolidityCAstType.get(typeId.substring(8)),"");
					}

				} else if (typeId.startsWith("t_enum$_")) {
					int typeEndIndex = typeId.indexOf("_$", 8);
					int idEndIndex = typeId.indexOf('_', typeEndIndex + 1);
					if (idEndIndex > 0) {
						CAstType et = getType(getDeclaration(Integer.valueOf(typeId.substring(typeEndIndex+2, idEndIndex)), null, context), context);
						return Pair.make(et, typeId.substring(idEndIndex));
					} else {
						CAstType et = getType(getDeclaration(Integer.valueOf(typeId.substring(typeEndIndex+2)), null, context), context);
						return Pair.make(et, "");				
					}
				} else if (typeId.startsWith("t_contract$_")) {
					int typeEndIndex = typeId.indexOf('_', 12);
					int idEndIndex = typeId.indexOf('_', typeEndIndex + 1);
					String name = typeId.substring(12, typeEndIndex);
					if (idEndIndex >= 0) {
						CAstType et = getType(getDeclaration(Integer.valueOf(typeId.substring(typeEndIndex+2, idEndIndex)), name, context), context);
						return Pair.make(et, typeId.substring(idEndIndex));
					} else {
						CAstType et = getType(getDeclaration(Integer.valueOf(typeId.substring(typeEndIndex+2)), name, context), context);
						return Pair.make(et, "");
					}
						

				} else if (typeId.startsWith("t_super$_")) {
					int typeEndIndex = typeId.indexOf('_', 9);
					int idEndIndex = typeId.indexOf('_', typeEndIndex + 1);
					if (idEndIndex >= 0) {
						CAstType et = getType(getDeclaration(Integer.valueOf(typeId.substring(typeEndIndex+2, idEndIndex)), null, context), context);
						return Pair.make(et, typeId.substring(idEndIndex));
					} else {
						CAstType et = getType(getDeclaration(Integer.valueOf(typeId.substring(typeEndIndex+2)), null, context), context);
						return Pair.make(et, "");
					}

				} else if (typeId.startsWith("t_userDefinedValueType$_")) {
					int typeEndIndex = typeId.indexOf('_', 24);
					int idEndIndex = typeId.indexOf('_', typeEndIndex + 1);
					if (idEndIndex >= 0) {
						CAstType et = getType(getDeclaration(Integer.valueOf(typeId.substring(typeEndIndex+2, idEndIndex)), null, context), context);
						return Pair.make(et, typeId.substring(idEndIndex));
					} else {
						CAstType et = getType(getDeclaration(Integer.valueOf(typeId.substring(typeEndIndex+2)), null, context), context);
						return Pair.make(et, "");
					}

				} else if (typeId.startsWith("t_struct$_")) {
					int typeEndIndex = typeId.indexOf('_', 10);
					int idEndIndex = typeId.indexOf('_', typeEndIndex + 1);
					String name = typeId.substring(10, typeEndIndex);
					CAstType et = getType(getDeclaration(Integer.valueOf(typeId.substring(typeEndIndex+2, idEndIndex)), name, context), context);
					String remaining = typeId.substring(idEndIndex);
					if (remaining.startsWith("_storage_ptr")) {
						remaining = remaining.substring(12);
					} else if (remaining.startsWith("_memory_ptr")) {
						remaining = remaining.substring(11);
					} else if (remaining.startsWith("_storage")) {
						remaining = remaining.substring(8);
					} else if (remaining.startsWith("_calldata_ptr")) {
						remaining = remaining.substring(13);
					}
					return Pair.make(et, remaining);
					
				} else if (typeId.startsWith("t_type$_")) {
					Pair<CAstType, String> x = parseNextTypeIdentifier(typeId.substring(8), context);
					if (x.snd.startsWith("_$")) {
						return Pair.make(x.fst, x.snd.substring(2));
					}
					
				} else if (typeId.startsWith("t_")) {
					int endIndex = typeId.indexOf('_', 2);
					if (endIndex > 0) {
						String type = typeId.substring(2, endIndex);
						String remaining = typeId.substring(endIndex);
						if (remaining.startsWith("_memory_ptr")) {
							remaining = remaining.substring(11);
						} else if (remaining.startsWith("_payable")) {
							remaining = remaining.substring(8);
						} else if (remaining.startsWith("_storage")) {
							remaining = remaining.substring(8);
						} else if (remaining.startsWith("_calldata_ptr")) {
							remaining = remaining.substring(13);
						}
						return Pair.make(SolidityCAstType.get(type), remaining);
					} else {
						return Pair.make(SolidityCAstType.get(typeId.substring(2)),"");
					}
				}
				
				throw new RuntimeException("don't understand type " + typeId);
			}
			
			private CAstType parseTypeIdentifier(String typeId, SolidityWalkContext context) {
				return parseNextTypeIdentifier(typeId, context).fst;
			}

			private <T extends CAstType> T findOrCreateType(JSONObject typeDefinition, SolidityWalkContext context, BiFunction<JSONObject, SolidityWalkContext, T> factory) {
				BiFunction<JSONObject, SolidityWalkContext, String> key = (k, c) -> k.getString("src") + (k.has("canonicalName")? k.getString("canonicalName"): k.getString("name"));
				return findOrCreateType(typeDefinition, key, context, factory);
			}
			
			@SuppressWarnings("unchecked")
			private <X, T extends CAstType> T findOrCreateType(JSONObject typeDefinition, BiFunction<JSONObject, SolidityWalkContext, X> key, SolidityWalkContext context, BiFunction<JSONObject, SolidityWalkContext, T> factory) {
				X typeName = key.apply(typeDefinition, context);
				if (entityTypes.containsKey(typeName)) {
					return (T) entityTypes.get(typeName);
				} else {
					T st = factory.apply(typeDefinition, context);
					entityTypes.put(typeName, st);
					return st;
				}
			}

			private CAstType[] getTypes(JSONArray ps, SolidityWalkContext context) {
				return Streams.stream(ps.iterator()).map(p -> getType((JSONObject)p, context)).toArray(i -> new CAstType[i]);
			}

			private CAstType getTupleType(List<JSONObject> node, SolidityWalkContext context) {
				return SolidityTupleType.get(node.stream().map(x -> x != null? getType(x, context): SolidityCAstType.get("void")).toArray(i -> new CAstType[i]));
			}

			private JSONObject findSuperCall(JSONObject superDecl, String name, List<CAstType> argTypes, SolidityWalkContext context) {
				for(Object e : superDecl.getJSONArray("nodes")) {
					if (e instanceof JSONObject) {
						JSONObject elt = (JSONObject)e;
						if ("FunctionDefinition".equals(elt.getString("nodeType"))) {
							FunctionType et = findOrCreateType(elt, this::makeFunctionKey, context, this::newFunctionType);
							if (argTypes.equals(et.getArgumentTypes()) && et.getName().startsWith(name)) {
								return elt;
							}
						}
					}
				}
				
				/*
				if (superDecl.has("baseContracts")) {
					for(Object spec : superDecl.getJSONArray("baseContracts")) {
						if (spec instanceof JSONObject) {
							JSONObject elt = (JSONObject)spec;
							JSONObject sd = getDeclaration(elt.getJSONObject("baseName"), context);
							JSONObject o = findSuperCall(sd, name, argTypes, context);
							if (o != null) {
								return o;
							}
						}
					}
				}
				*/
				
				return null;
			}

			private Position getLocation(String string) {
				String[] bits = string.split(":");
				int startOffset = Integer.valueOf(bits[0]);
				int endOffset = startOffset + Integer.valueOf(bits[1]);
				String fileName = tree.getString("absolutePath");
				return new Position() {
					public int hashCode() {
						return (startOffset+endOffset)*fileName.hashCode();
					}
					
					public boolean equals(Object o) {
						return o instanceof Position &&
							getFirstOffset() == ((Position)o).getFirstOffset() &&
							getLastOffset() == ((Position)o).getLastOffset() &&
							getURL().equals(((Position)o).getURL());
					}

					private int getLine(int offset) {
						return IntStream.range(0, linePositionMap.length).filter(i -> linePositionMap[i] > offset)
								.findFirst().orElse(linePositionMap.length) - 1;
					}

					private int getColumn(int offset) {
						int line = IntStream.range(0, linePositionMap.length).filter(i -> linePositionMap[i] > offset)
								.findFirst().orElse(linePositionMap.length);
						return offset - linePositionMap[line - 1];
					}

					@Override
					public int getFirstCol() {
						return getColumn(startOffset);
					}

					@Override
					public int getFirstLine() {
						return getLine(startOffset);
					}

					@Override
					public int getFirstOffset() {
						return startOffset;
					}

					@Override
					public int getLastCol() {
						return getColumn(endOffset);
					}

					@Override
					public int getLastLine() {
						return getLine(endOffset);
					}

					@Override
					public int getLastOffset() {
						return endOffset;
					}

					@Override
					public int compareTo(SourcePosition o) {
						return o.getFirstOffset() - getFirstOffset();
					}

					@Override
					public Reader getReader() throws IOException {
						return SolidityJSONTranslator.this.getReader();
					}

					@Override
					public URL getURL() {
						try {
							return new File(fileName).toURI().toURL();
						} catch (MalformedURLException | IllegalArgumentException e) {
							assert false : e;
							return null;
						}
					}

					@Override
					public String toString() {
						return "[" + getFirstOffset() + "-" + getLastOffset() + "]";
					}
				};
			}

			CAstOperator translateOpcode(String t) {
				switch (t) {
				case "!":
					return CAstOperator.OP_NOT;
				case "**":
					return CAstOperator.OP_POW;
				case "+":
				case "+=":
				case "++":
					return CAstOperator.OP_ADD;
				case "-":
				case "-=":
				case "--":
					return CAstOperator.OP_SUB;
				case "*":
				case "*=":
					return CAstOperator.OP_MUL;
				case "/":
				case "/=":
					return CAstOperator.OP_DIV;
				case "%":
				case "%=":
					return CAstOperator.OP_MOD;
				case "==":
					return CAstOperator.OP_EQ;
				case "!=":
					return CAstOperator.OP_NE;
				case "<":
					return CAstOperator.OP_LT;
				case "<=":
					return CAstOperator.OP_LE;
				case ">":
					return CAstOperator.OP_GT;
				case ">=":
					return CAstOperator.OP_GE;
				case "^":
				case "^=":
					return CAstOperator.OP_BIT_XOR;
				case "|":
				case "|=":
					return CAstOperator.OP_BIT_OR;
				case "&":
				case "&=":
					return CAstOperator.OP_BIT_AND;
				case "<<":
				case "<<=":
					return CAstOperator.OP_LSH;
				case ">>":
				case ">>=":
					return CAstOperator.OP_RSH;
				case ">>>":
				case ">>>=":
					return CAstOperator.OP_URSH;
				case "~":
					return CAstOperator.OP_BITNOT;
				default:
					return null;
				}
			}

			private CAstNode record(CAstNode expr, Position location, CAstType type, SolidityWalkContext context) {
				context.getNodeTypeMap().add(expr, type);
				return record(expr, location, context);
			}
			
			private CAstNode record(CAstNode expr, Position location, SolidityWalkContext context) {
				context.pos().setPosition(expr, location);
				return expr;
			}

			private CAstNode getSelfPtr(SolidityWalkContext context) {
			    CAstNode thisPtr = ast.makeNode(CAstNode.THIS);
			    CAstType methodType = context.type();
			    context.getNodeTypeMap().add(thisPtr, methodType);
			    CAstNode selfPtr = ast.makeNode(CAstNode.OBJECT_REF, thisPtr, ast.makeConstant("self"));
			    context.getNodeTypeMap().add(selfPtr, ((FunctionType)methodType).getDeclaringType());
			    return selfPtr;				
			}
			
			public CAstNode handleIdentifierDeclaration(JSONObject decl, Position location, SolidityWalkContext context) {
				return (new JsonNodeTypeOnlyVisitor<CAstNode>() {
					@SuppressWarnings("unused")
					public CAstNode visitVariableDeclaration(JSONObject decl, Void ignore) {
						if (decl.getBoolean("constant")) {
							return TranslationVisitor.this.visit(decl.getJSONObject("value"), context);
						} else {
							CAstNode name = ast.makeConstant(decl.getString("name"));
							CAstType type = getType(decl.getJSONObject("typeName"), context);
							if (decl.has("stateVariable") && decl.getBoolean("stateVariable")) {
								CAstNode self = getSelfPtr(context);
								return record(ast.makeNode(CAstNode.OBJECT_REF, self, name), location, type, context);
							} else {
								return record(ast.makeNode(CAstNode.VAR, name), location, type, context);
							}
						}
					}

					@SuppressWarnings("unused")
					public CAstNode visitFunctionDefinition(JSONObject decl, Void ignore) {
						CAstType et = findOrCreateType(decl, TranslationVisitor.this::makeFunctionKey, context, TranslationVisitor.this::newFunctionType);
						String name = decl.has("constructor") && decl.getBoolean("constructor")? "<init>": decl.getString("name");
						CAstNode selfPtr = null;
						if (context.contract() != null) {
							selfPtr = getSelfPtr(context);
							return record(ast.makeNode(CAstNode.OBJECT_REF, selfPtr, ast.makeConstant(name)), location, et, context);
						} else if (decl.has("kind") && "freeFunction".equals(decl.getString("kind"))) {
							return record(ast.makeConstant(getType(decl, context)), location, et, context);
						} else {
							return record(ast.makeNode(CAstNode.VAR, ast.makeConstant(name)), location, et, context);

						}
					}

					@SuppressWarnings("unused")
					public CAstNode visitEventDefinition(JSONObject decl, Void ignore) {
						CAstType et = findOrCreateType(decl, context, TranslationVisitor.this::newFunctionType);
						CAstNode selfPtr = getSelfPtr(context);
						return record(ast.makeNode(CAstNode.OBJECT_REF, selfPtr, ast.makeConstant(decl.getString("name"))), location, et, context);
					}

					@SuppressWarnings("unused")
					public CAstNode visitErrorDefinition(JSONObject decl, Void ignore) {
						CAstType et = findOrCreateType(decl, context, TranslationVisitor.this::newFunctionType);
						CAstNode selfPtr = getSelfPtr(context);
						return record(ast.makeNode(CAstNode.OBJECT_REF, selfPtr, ast.makeConstant(decl.getString("name"))), location, et, context);
					}

					@SuppressWarnings("unused")
					public CAstNode visitContractDefinition(JSONObject decl, Void ignore) {
						CAstType vt = findOrCreateType(decl, context, TranslationVisitor.this::newContractType);
						return record(ast.makeNode(CAstNode.TYPE_LITERAL_EXPR, ast.makeConstant(vt.getName())), location, vt, context);
					}
					
					@SuppressWarnings("unused")
					public CAstNode visitStructDefinition(JSONObject decl, Void ignore) {
						CAstType vt = findOrCreateType(decl, context, TranslationVisitor.this::newStructType);
						return record(ast.makeNode(CAstNode.TYPE_LITERAL_EXPR, ast.makeConstant(vt.getName())), location, vt, context);
					}

					@SuppressWarnings("unused")
					public CAstNode visitEnumDefinition(JSONObject decl, Void ignore) {
						CAstType et = SolidityCAstType.get(decl.getString("canonicalName"));
						return record(ast.makeNode(CAstNode.TYPE_LITERAL_EXPR, ast.makeConstant(et.getName())), location, et, context);
					}

					@SuppressWarnings("unused")
					public CAstNode visitUserDefinedTypeName(JSONObject decl, Void ignore) {
						return handleIdentifierDeclaration(decl.getJSONObject("referencedDeclaration"), location, context);
					}
					@SuppressWarnings("unused")
					public CAstNode visitUserDefinedValueTypeDefinition(JSONObject decl, Void ignore) {
						CAstType ut = getType(decl.getJSONObject("underlyingType"), context);
						return record(ast.makeNode(CAstNode.TYPE_LITERAL_EXPR, ast.makeConstant(ut.getName())), location, ut, context);
					}
					
				}).visit(decl, null);
			}

			private SolidityWalkContext contractContext(JSONObject contract, SolidityWalkContext parent) {

				class ContractContext implements VariableContainerContext, FunctionContainerContext {
					private final Collection<CAstEntity> vars = new ArrayList<>();
					private final Collection<CAstEntity> functions = new ArrayList<>();

					@Override
					public WalkContext<SolidityWalkContext, JSONObject> getParent() {
						return parent;
					}

					@Override
					public Collection<CAstEntity> variables() {
						return vars;
					}

					@Override
					public Collection<CAstEntity> functions() {
						return functions;
					}

					@Override
					public JSONObject contract() {
						return contract;
					}

					@Override
					public CAstType type() {
						return findOrCreateType(contract, parent, TranslationVisitor.this::newContractType);
					}
					
					public String toString() {
						return "context for contract " + contract.getString("name");
					}
				}

				return new ContractContext();
			}

			private SolidityWalkContext structContainerContext(JSONObject structDefinition, SolidityWalkContext parent) {
				return new VariableContainerContext() {
					private final Collection<CAstEntity> vars = new ArrayList<>();

					@Override
					public JSONObject contract() {
						return structDefinition;
					}

					@Override
					public WalkContext<SolidityWalkContext, JSONObject> getParent() {
						return parent;
					}

					@Override
					public Collection<CAstEntity> variables() {
						return vars;
					}
				};
			}

			private SolidityWalkContext codeContext(FunctionEntity funEntity, SolidityWalkContext context) {
				return new VariableContainerContext() {
					private final Collection<CAstEntity> vars = new ArrayList<>();

					@Override
					public WalkContext<SolidityWalkContext, JSONObject> getParent() {
						return context;
					}

					@Override
					public Collection<CAstEntity> variables() {
						return vars;
					}

					@Override
					public CAstType type() {
						return funEntity.getType();
					}
					
					public String toString() {
						return "context for " + funEntity;
					}

					
					@Override
					public CAstControlFlowRecorder cfg() {
						return funEntity.getControlFlow();
					}

					@Override
					public CAstSourcePositionRecorder pos() {
						return funEntity.getSourceMap();
					}

					@Override
					public CAstNodeTypeMapRecorder getNodeTypeMap() {
						return funEntity.getNodeTypeMap();
					}	
				};
			}

			CallableEntity visitCallableDefinition(JSONObject def, CAstType retType, boolean isCtor, SolidityWalkContext context) {
				ids.put(def.getInt("id"), def);
				
				JSONArray parameters = def.getJSONObject("parameters").getJSONArray("parameters");
				
				Streams.stream(parameters.iterator()).forEach(x -> { 
					JSONObject p = (JSONObject)x;
					ids.put(p.getInt("id"), p);
				});
				
				String funName = isCtor ? "<init>" : def.getString("name");
				CAstType[] parameterTypes = Streams.stream(parameters.iterator())
						.map(x -> getType(((JSONObject) x).getJSONObject("typeName"), context)).toArray(x -> new CAstType[x]);

				JSONObject cls = context.contract();
				CAstType.Class selfType = (cls != null) ? findOrCreateType(cls, context, TranslationVisitor.this::newContractType)
						: null;

				CAstType.Function funType = FunctionType.findOrCreate(funName, selfType, retType, parameterTypes);
				Position loc = getLocation(def.getString("src"));

				Position[] parameterLocs = Streams.stream(parameters.iterator())
						.map(x -> getLocation(((JSONObject) x).getString("src"))).toArray(x -> new Position[x]);

				String[] parameterNames = Streams.stream(parameters.iterator())
						.map(x -> ((JSONObject) x).getString("name")).toArray(x -> new String[x]);

				Set<CAstQualifier> qualSet = HashSetFactory.make();

				if (isCtor) {
					qualSet.add(CAstQualifier.PUBLIC);
				} else {
					if (def.has("visibility")) {
					switch (def.getString("visibility")) {
					case "default":
					case "private":
						qualSet.add(CAstQualifier.PRIVATE);
						break;
					case "internal":
						qualSet.add(CAstQualifier.PROTECTED);
						break;
					case "external":
						qualSet.add(CAstQualifier.EXTERNAL);
					case "public":
						qualSet.add(CAstQualifier.PUBLIC);
					}
					}
				}
				if (def.has("virtual") && def.getBoolean("virtual")) {
					qualSet.add(CAstQualifier.VIRTUAL);
				}

				boolean isEvent = "EventDefinition".equals(def.get("nodeType"));
				boolean isError = "ErrorDefinition".equals(def.get("nodeType"));

				if (isEvent || isError) {
					return new EventEntity(funName, funType, parameterNames, loc, null, parameterLocs, qualSet, null);
				} else {
					return new FunctionEntity(funName, funType, parameterNames, loc, null, parameterLocs, qualSet,
							null);
				}
			}

			@SuppressWarnings("unused")
			public CAstNode visitAssignment(JSONObject o, SolidityWalkContext context) {
				CAstNode lhs = visit(o.getJSONObject("leftHandSide"), context);
				CAstNode rhs = visit(o.getJSONObject("rightHandSide"), context);

			    String op = o.getString("operator");
			    
			    return record(("=".equals(op)?
			               ast.makeNode(CAstNode.ASSIGN, lhs, rhs):
			               ast.makeNode(CAstNode.ASSIGN_POST_OP, lhs, rhs, translateOpcode(op))),
			            getLocation(o.getString("src")), null, context);
			}
		
			@SuppressWarnings("unused")
			public CAstNode visitBinaryOperation(JSONObject o, SolidityWalkContext context) {
				String op = o.getString("operator");
				CAstNode expr;
				CAstNode left = visit(o.getJSONObject("leftExpression"), context);
				if (left == null) {
					left = visit(o.getJSONObject("leftExpression"), context);
				}
				CAstNode right = visit(o.getJSONObject("rightExpression"), context);
				if ("&&".equals(op)) {
					expr = ast.makeNode(CAstNode.IF_EXPR, left, right, ast.makeConstant(false));
				} else if ("||".equals(op)) {
					expr = ast.makeNode(CAstNode.IF_EXPR, left, ast.makeConstant(true), right);
				} else {
					CAstNode operator = translateOpcode(op);
					expr = ast.makeNode(CAstNode.BINARY_EXPR, operator, left, right);
				}

				record(expr, getLocation(o.getString("src")), getType(o, context), context);

				return expr;
			}

			public CAstNode visitBlock(JSONObject o, SolidityWalkContext context) {
				return ast.makeNode(CAstNode.BLOCK_STMT, Streams.stream(o.getJSONArray("statements").iterator())
						.map(n -> visit((JSONObject) n, context)).toList());
			}

			@SuppressWarnings("unused")
			public CAstNode visitBreak(JSONObject o, SolidityWalkContext context) {
				CAstNode result = record(ast.makeNode(CAstNode.GOTO), getLocation(o.getString("src")), context);
				context.cfg().map(result, result);
				context.cfg().add(result, context.getBreakFor(null), null);
				return result;
			}
			
			@SuppressWarnings("unused")
			public CAstNode visitConditional(JSONObject o, SolidityWalkContext context) {
				return record(ast.makeNode(CAstNode.IF_EXPR, 
						visit(o.getJSONObject("condition"), context),
						visit(o.getJSONObject("trueExpression"), context),
						visit(o.getJSONObject("falseExpression"), context)),
						getLocation(o.getString("src")),
						null,
						context);	
			}

			@SuppressWarnings("unused")
			public CAstNode visitContractDefinition(JSONObject o, SolidityWalkContext context) {
				ids.put(o.getInt("id"), o);
				CAstType.Class contractType = (CAstType.Class) getType(o, context);
				SolidityWalkContext child = contractContext(o, context);

				for(String s : new String[] {"nodes", "baseContracts"}) {
					if (o.has(s)) {
						o.getJSONArray(s).forEach(m -> {
							visit((JSONObject) m, child);
						});
					}
				}
				
				Set<CAstEntity> elts = HashSetFactory.make(
					child.variables()).stream().collect(Collectors.toSet());
				elts.addAll(child.functions().stream().collect(Collectors.toSet()));

				ContractEntity contractEntity = new ContractEntity(contractType, getLocation(o.getString("src")), null,
						elts);

				context.addScopedEntity(null, contractEntity);

				return ast.makeNode(CAstNode.EMPTY);
			}

			@SuppressWarnings("unused")
			public CAstNode visitContinue(JSONObject o, SolidityWalkContext context) {
				CAstNode result = record(ast.makeNode(CAstNode.GOTO), getLocation(o.getString("src")), context);
				context.cfg().map(result, result);
				context.cfg().add(result, context.getContinueFor(null), null);
				return result;
			}

			@SuppressWarnings("unused")
			public CAstNode visitElementaryTypeNameExpression(JSONObject o, SolidityWalkContext context) {
				CAstType type = getType(o.getJSONObject("typeName"), context);
				return record(ast.makeNode(CAstNode.TYPE_LITERAL_EXPR, ast.makeConstant(type.getName())), getLocation(o.getString("src")), type, context);
			}
			
			@SuppressWarnings("unused")
			public CAstNode visitEmitStatement(JSONObject o, SolidityWalkContext context) {
				return record(visit(o.getJSONObject("eventCall"), context), getLocation(o.getString("src")), null, context);
			}

			@SuppressWarnings("unused")
			public CAstNode visitEnumDefinition(JSONObject o, SolidityWalkContext context) {
				ids.put(o.getInt("id"), o);
				EnumType et = findOrCreateType(o, context, TranslationVisitor.this::newEnumType);
				
				EnumEntity enumEntity = new EnumEntity(et, getLocation(o.getString("src")), o.has("nameLocation")? getLocation(o.getString("nameLocation")): null);
				context.addScopedEntity(null, enumEntity);

				return ast.makeNode(CAstNode.EMPTY);
			}

			@SuppressWarnings("unused")
			public CAstNode visitErrorDefinition(JSONObject o, SolidityWalkContext context) {
				ids.put(o.getInt("id"), o);
				
			    EventEntity funEntity = (EventEntity) visitCallableDefinition(o, null, false, context);
			    
			    context.registerFunction(o.getString("name"), funEntity);

			     return ast.makeNode(CAstNode.EMPTY);
			}

			@SuppressWarnings("unused")
			public CAstNode visitEventDefinition(JSONObject o, SolidityWalkContext context) {
				ids.put(o.getInt("id"), o);
				
			    EventEntity funEntity = (EventEntity) visitCallableDefinition(o, null, false, context);
			    
			    context.registerFunction(o.getString("name"), funEntity);

			     return ast.makeNode(CAstNode.EMPTY);
			}

			@SuppressWarnings("unused")
			public CAstNode visitExpressionStatement(JSONObject o, SolidityWalkContext context) {
				return record(visit(o.getJSONObject("expression"), context), getLocation(o.getString("src")), null, context);
			}

			 private static class SolidityLoopContext extends TranslatorToCAst.LoopContext<SolidityWalkContext, JSONObject>
		        implements SolidityWalkContext {

		      SolidityLoopContext(SolidityWalkContext parent, JSONObject breakTo, JSONObject continueTo) {
		        super(parent, breakTo, continueTo, null);
		      }

		      private boolean continued = false;
		      private boolean broke = false;

		      @Override
		      public JSONObject getContinueFor(String l) {
		        continued = true;
		        return super.getContinueFor(l);
		      }

		      @Override
		      public JSONObject getBreakFor(String l) {
		        broke = true;
		        return super.getBreakFor(l);
		      }
		    }
			
			@SuppressWarnings("unused")
			public CAstNode visitForStatement(JSONObject o, SolidityWalkContext context) {
				CAstNode init = o.has("initializationExpression")? visit(o.getJSONObject("initializationExpression"), context): ast.makeNode(CAstNode.EMPTY);
				CAstNode test = visit(o.getJSONObject("condition"), context);
				CAstNode update = o.has("loopExpression")? visit(o.getJSONObject("loopExpression"), context): ast.makeNode(CAstNode.EMPTY);
				
				JSONObject contLabel = JSONObject.fromJson("{\"nodeType\": \"Continue\"}", JSONObject.class);
				JSONObject breakLabel = JSONObject.fromJson("{\"nodeType\": \"Break\"}", JSONObject.class);
				SolidityLoopContext lc = new SolidityLoopContext(context, breakLabel, contLabel);
				CAstNode body = visit(o.getJSONObject("body"), lc);
				
				if (lc.continued) {
					CAstNode cs = ast.makeNode(CAstNode.LABEL_STMT, ast.makeConstant("cont" + idx++), ast.makeNode(CAstNode.EMPTY));
					context.cfg().map(contLabel, cs);
					body = ast.makeNode(CAstNode.BLOCK_STMT, body, cs);
				}
				
				CAstNode result = ast.makeNode(CAstNode.BLOCK_STMT, 
					init, 
					ast.makeNode(CAstNode.LOOP, 
						test,
						ast.makeNode(CAstNode.BLOCK_STMT, body, update)));
				
				if (lc.broke) {
					CAstNode bs = ast.makeNode(CAstNode.LABEL_STMT, ast.makeConstant("break" + idx++), ast.makeNode(CAstNode.EMPTY));
					context.cfg().map(breakLabel, bs);
					result = ast.makeNode(CAstNode.BLOCK_STMT, result, bs);
				}
				
				return result;
			}

			@SuppressWarnings("unused")
			public CAstNode visitFunctionCall(JSONObject o, SolidityWalkContext context) {
				CAstNode modular = modularBuiltin(o, context);
				if (modular != null) {
					return modular;
				}
				CAstNode fun = visit(o.getJSONObject("expression"), context);
				if (fun == null) {
					visit(o.getJSONObject("expression"), context);
				}
				CAstNode[] args = Streams.concat(Streams.stream(Optional.of(ast.makeNode(CAstNode.EMPTY))), Streams.stream(o.getJSONArray("arguments").iterator()).map(v -> (JSONObject)v).map(v -> visit(v, context))).toArray(i -> new CAstNode[i]);
				return record(ast.makeNode(CAstNode.CALL, fun, args), getLocation(o.getString("src")), getType(o, context), context);
			}

			/**
			 * The builtins {@code mulmod(a, b, m)} and {@code addmod(a, b, m)} as {@code (a * b) % m}
			 * and {@code (a + b) % m}, the same lowering the Yul builtins get, so a remainder reads
			 * the same whichever syntax computes it. Returns null for any other call.
			 */
			private CAstNode modularBuiltin(JSONObject o, SolidityWalkContext context) {
				JSONObject callee = o.getJSONObject("expression");
				if (!"Identifier".equals(callee.getString("nodeType")) || callee.optInt("referencedDeclaration", 0) >= 0) {
					return null;
				}
				CAstOperator combine;
				switch (callee.getString("name")) {
				case "mulmod":
					combine = CAstOperator.OP_MUL;
					break;
				case "addmod":
					combine = CAstOperator.OP_ADD;
					break;
				default:
					return null;
				}
				JSONArray args = o.getJSONArray("arguments");
				CAstNode a = visit(args.getJSONObject(0), context);
				CAstNode b = visit(args.getJSONObject(1), context);
				CAstNode m = visit(args.getJSONObject(2), context);
				return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_MOD, ast.makeNode(CAstNode.BINARY_EXPR, combine, a, b), m),
						getLocation(o.getString("src")), getType(o, context), context);
			}

			@SuppressWarnings("unused")
			public CAstNode visitFunctionCallOptions(JSONObject o, SolidityWalkContext context) {
				return visit(o.getJSONObject("expression"), context);
			}

			@SuppressWarnings("unused")
			public CAstNode visitFunctionDefinition(JSONObject o, SolidityWalkContext context) {
				CAstType retType;
				JSONArray rets = o.getJSONObject("returnParameters").getJSONArray("parameters");
				if (rets.length() == 0) {
					retType = null;
				} else if (rets.length() == 1) {
					retType = getType(rets.getJSONObject(0).getJSONObject("typeName"), context);
				} else {
					retType = SolidityTupleType.get(getTypes(rets, context));
				}

				FunctionEntity funEntity = (FunctionEntity) visitCallableDefinition(o, retType,
						"constructor".endsWith(o.getString("kind")), context);

				if (o.has("mutability")) {
					String mutability = o.getString("mutability");
					if ("pure".equals(mutability)) {
						funEntity.addQualifier(CAstQualifier.PURE);
					} else if ("view".equals(mutability)) {
						funEntity.addQualifier(CAstQualifier.CONST);
					}
				}
				
				if (!ids.containsKey(o.getInt("scope")) || 
					!ids.get(o.getInt("scope")).has("contractKind") ||
					"library".equals(ids.get(o.getInt("scope")).get("contractKind"))) {
					funEntity.addQualifier(CAstQualifier.STATIC);
				}

				if (!o.getBoolean("implemented")) {
					funEntity.addQualifier(CAstQualifier.ABSTRACT);

				} else {
					SolidityWalkContext child = codeContext(funEntity, context);

					Streams
					.stream(o.getJSONObject("returnParameters").getJSONArray("parameters").iterator())
					.map(d -> (JSONObject)d)
					.filter(d -> d.has("name") && !"".equals(d.getString("name")))
					.forEach(d -> visit(d, child));
					
					JSONArray retParams = o.getJSONObject("returnParameters").getJSONArray("parameters");
					List<String> retNames = new ArrayList<>();
					for (int i = 0; i < retParams.length(); i++) {
						JSONObject p = retParams.getJSONObject(i);
						retNames.add(p.has("name") && !"".equals(p.getString("name")) ? p.getString("name")
								: "$ret" + i + "$" + idx++);
					}
					JSONObject exitKey = JSONObject.fromJson("{\"nodeType\": \"FunctionExit\"}", JSONObject.class);

					CAstNode body;
					if (retNames.isEmpty()) {
						body = visit(o.getJSONObject("body"), child);
					} else {
						CAstType retTuple = SolidityTupleType.get(Streams.stream(retParams.iterator())
							.map(x -> getType((JSONObject)x, context))
							.toArray(i -> new CAstType[i]));
						returnTargets.push(new ReturnTarget(retNames, retTuple, exitKey));
						try {
							body = visit(o.getJSONObject("body"), child);
						} finally {
							returnTargets.pop();
						}
					}
					
					if ("constructor".equals(o.getString("kind"))) {
						Streams.stream(o.getJSONArray("modifiers").iterator()).forEach(x -> {
							JSONObject scs = (JSONObject) x;
							JSONObject superDecl = getDeclaration(scs.getJSONObject("modifierName"), context);
							List<CAstType> argTypes = Streams.stream(scs.getJSONArray("arguments").iterator())
									.map(a -> getType((JSONObject) a, context)).toList();
							if ("ContractDefinition".equals(superDecl.getString("nodeType"))) {
								JSONObject ctor = findSuperCall(superDecl, "<init>", argTypes, context);
								CAstNode callee;
								if ((callee = handleIdentifierDeclaration(ctor,
										getLocation(scs.getString("src")), child)) != null) {
									CAstNode[] args = Streams.stream(scs.getJSONArray("arguments").iterator())
											.map(a -> visit((JSONObject) a, child)).toArray(n -> new CAstNode[n]);
									record(ast.makeNode(CAstNode.CALL, callee, args), getLocation(scs.getString("src")),
											null, context);
								}
							}
						});
					}

					List<CAstNode> retDecls = new ArrayList<>();
					for (int i = 0; i < retParams.length(); i++) {
						CAstSymbol symbol = new CAstSymbolImpl(retNames.get(i), getType(retParams.getJSONObject(i), context), false);
						retDecls.add(ast.makeNode(CAstNode.BLOCK_STMT,
								ast.makeNode(CAstNode.DECL_STMT, ast.makeConstant(symbol)),
								ast.makeNode(CAstNode.ASSIGN,
									ast.makeNode(CAstNode.VAR, ast.makeConstant(retNames.get(i))),
									ast.makeConstant(0))));
					}

					if (!retNames.isEmpty()) {
						CAstType tt = SolidityTupleType.get(Streams.stream(retParams.iterator())
							.map(x -> getType((JSONObject)x, context))
							.toArray(i -> new CAstType[i]));

						CAstNode[] retVals = retNames.stream()
						   .map(name -> ast.makeNode(CAstNode.VAR, ast.makeConstant(name)))
						   .toArray(i -> new CAstNode[i]);

						// the single exit every return jumps to, so the returned values merge here
						CAstNode exitLabel = ast.makeNode(CAstNode.LABEL_STMT, ast.makeConstant("exit" + idx++),
							ast.makeNode(CAstNode.EMPTY));
						child.cfg().map(exitKey, exitLabel);

						CAstNode ret = ast.makeNode(CAstNode.RETURN, 
							(retVals.length == 1)?
							retVals[0]:
							ast.makeNode(CAstNode.NEW,
								ast.makeConstant(tt),
								retVals));
							
						body = ast.makeNode(CAstNode.BLOCK_STMT, body, exitLabel, ret);
					}

					retDecls.add(body);
					body = ast.makeNode(CAstNode.BLOCK_STMT, retDecls);

					funEntity.setAst(body);
				}

				context.registerFunction(o.getString("name"), funEntity);
				
				return ast.makeNode(CAstNode.EMPTY);
			}

			private boolean isMagicIdentifier(JSONObject id) {
				return id.getInt("referencedDeclaration") < 0;
			}
			
			@SuppressWarnings("unused")
			public CAstNode visitIdentifier(JSONObject o, SolidityWalkContext context) {
				if (isMagicIdentifier(o)) {
					if ("this".equals(o.getString("name"))) {
						return getSelfPtr(context);
					} else {
						return record(ast.makeNode(CAstNode.PRIMITIVE, ast.makeConstant(o.getString("name")), ast.makeConstant(getType(o, context))), getLocation(o.getString("src")), getType(o, context), context);
					}
				} else {
					return handleIdentifierDeclaration(getDeclaration(o, context), getLocation(o.getString("src")), context);
				}
			}

			@SuppressWarnings("unused")
			public CAstNode visitIfStatement(JSONObject o, SolidityWalkContext context) {
				if (o.has("falseBody")) {
					return record(ast.makeNode(CAstNode.IF_STMT, 
							visit(o.getJSONObject("condition"), context),
							visit(o.getJSONObject("trueBody"), context),
							visit(o.getJSONObject("falseBody"), context)),
							getLocation(o.getString("src")),
							null,
							context);	
					} else {
						return record(ast.makeNode(CAstNode.IF_STMT, 
								visit(o.getJSONObject("condition"), context),
								visit(o.getJSONObject("trueBody"), context)),
								getLocation(o.getString("src")),
								null,
								context);				
				}
			}

			@SuppressWarnings("unused")
			public CAstNode visitImportDirective(JSONObject o, SolidityWalkContext context) {
				if (o.has("symbolAliases")) {
					JSONArray aliases = o.getJSONArray("symbolAliases");
					for(int i = 0; i < aliases.length(); i++) {
						JSONObject alias = aliases.getJSONObject(i);
						if (alias.has("local")) {
							JSONObject decl = getDeclaration(alias.getJSONObject("foreign"), context);
							context.renamings().put(alias.getString("local"), decl);
						}
					}
				}
				return ast.makeNode(CAstNode.EMPTY);
			}

			@SuppressWarnings("unused")
			public CAstNode visitIndexAccess(JSONObject o, SolidityWalkContext context) {
				CAstNode obj = visit(o.getJSONObject("baseExpression"), context);
				if (o.has("indexExpression")) {
					CAstType eltType = getType(o, context);
					JSONObject idxExpr = o.getJSONObject("indexExpression");
					return record(ast.makeNode(CAstNode.ARRAY_REF, obj, ast.makeConstant(eltType), visit(idxExpr, context)), getLocation(o.getString("src")), eltType, context);
				} else {
					CAstType bt = getType(o.getJSONObject("baseExpression"), context);
					CAstType at = SolidityArrayType.get(bt);
					return record(ast.makeNode(CAstNode.TYPE_LITERAL_EXPR, ast.makeConstant(at.getName())),getLocation(o.getString("src")), at, context); 
				}
			}

			@SuppressWarnings("unused")
			public CAstNode visitIndexRangeAccess(JSONObject o, SolidityWalkContext context) {
				CAstNode obj = visit(o.getJSONObject("baseExpression"), context);
				CAstType eltType = getType(o, context);
				JSONObject stIdxExpr = o.getJSONObject("startExpression");
				JSONObject endIdxExpr = o.getJSONObject("startExpression");
				return record(ast.makeNode(CAstNode.ARRAY_REF, obj, ast.makeConstant(eltType), visit(stIdxExpr, context), visit(endIdxExpr, context)), getLocation(o.getString("src")), eltType, context);
			}
			
			@SuppressWarnings("unused")
			public CAstNode visitInheritanceSpecifier(JSONObject o, SolidityWalkContext context) {
				CAstType superType = getType(getDeclaration(o.getJSONObject("baseName"), context), context);
				supers.get(context.type()).add(superType.getName());
				return ast.makeNode(CAstNode.EMPTY);
			}
	
			@SuppressWarnings("unused")
			public CAstNode visitLiteral(JSONObject o, SolidityWalkContext context) {
				switch (o.getString("kind")) {
				case "string": return record(ast.makeConstant(o.getString("value")), getLocation(o.getString("src")), getType(o, context), context);
				case "bool": return record(ast.makeConstant(o.getBoolean("value")), getLocation(o.getString("src")), getType(o, context), context);
				case "number": 
					Number n;
					String rawValue = o.getString("value").replace("_", "");
					BigInteger unit = o.has("subdenomination") && !o.isNull("subdenomination")
							? SUBDENOMINATIONS.get(o.getString("subdenomination")) : null;
					if (unit != null) {
						// "1 weeks" is 604800 and "0.5 ether" is 5e17: the unit is part of the value
						BigDecimal scaled = (rawValue.startsWith("0x") ? new BigDecimal(new BigInteger(rawValue.substring(2), 16))
								: new BigDecimal(rawValue)).multiply(new BigDecimal(unit));
						rawValue = scaled.toBigIntegerExact().toString();
					}
					try {
						int radix = 10;
						String biv = rawValue;
						if (biv.startsWith("0x")) {
							biv = biv.substring(2);
							radix = 16;
						}
						BigInteger i = new BigInteger(biv, radix);
					try {
						n = Integer.valueOf(i.intValueExact());
					} catch (ArithmeticException e) {
						try {
							n = Long.valueOf(i.longValueExact());						
						} catch (ArithmeticException e1) {
							n = i;
						}
					}
					} catch (NumberFormatException e) {
						try {
							n = new BigDecimal(rawValue);
						} catch (NumberFormatException e1) {
							n = Integer.decode(rawValue);
						}
					}
					return record(ast.makeConstant(n), getLocation(o.getString("src")), getType(o, context), context);
				default:
					return ast.makeNode(CAstNode.EMPTY);
				}
			} 
	
			@SuppressWarnings("unused")
			public CAstNode visitUncheckedBlock(JSONObject o, SolidityWalkContext context) {
				return visitBlock(o, context);
			}
			
			@SuppressWarnings("unused")
			public CAstNode visitMemberAccess(JSONObject o, SolidityWalkContext context) {
				JSONObject decl = getDeclaration(o, context);
				if (decl != null && !o.getString("memberName").equals(decl.getString("name"))) {
					decl = getDeclaration(o.getInt("referencedDeclaration"), o.getString("memberName"), context);
				}
				if (decl == null && o.has("referencedDeclaration")) {
					decl = getDeclaration(o.getInt("referencedDeclaration"), o.getString("memberName"), context);
				}
				if (decl == null) {
					CAstNode baseObj = visit(o.getJSONObject("expression"), context);
					if (baseObj == null) {
						baseObj = visit(o.getJSONObject("expression"), context);
					}
					return record(
							ast.makeNode(CAstNode.OBJECT_REF, 
								baseObj,
								ast.makeConstant(o.getString("memberName"))),
							getLocation(o.getString("src")), getType(o, context), context);
				}
				return new JsonNodeTypeOnlyVisitor<CAstNode>() {
					public CAstNode visitVariableDeclaration(JSONObject vd, Void ignore) {
						if (vd.has("constant") && vd.getBoolean("constant")) {
							return TranslationVisitor.this.visit(vd.getJSONObject("value"), context);
						} else {
							return visitNode(o, null);
						}
					}

					public CAstNode visitCallableDefinition(JSONObject fd, Void ignore) {
						FunctionType type = findOrCreateType(fd, TranslationVisitor.this::makeFunctionKey, context, TranslationVisitor.this::newFunctionType);
						CAstNode ref = makeRef(o, type);
						context.getNodeTypeMap().add(ref, type);
						return ref;
					}

					public CAstNode visitEnumDefinition(JSONObject ed, Void ignore) {
						CAstType et = getType(ed, context);
						return record(
								ast.makeConstant(et),
								getLocation(o.getString("src")), et, context);
					}
	
					public CAstNode visitEnumValue(JSONObject ev, Void ignore) {
						CAstNode te = TranslationVisitor.this.visit(o.getJSONObject("expression"), context);
						CAstType et = context.getNodeTypeMap().getNodeType(te);
						return record(
								ast.makeNode(CAstNode.NEW,
									ast.makeConstant(et),
									ast.makeConstant(o.getString("memberName"))),
								getLocation(o.getString("src")), et, context);
					}
	
					public CAstNode visitFunctionDefinition(JSONObject fd, Void ignore) {
						return visitCallableDefinition(fd, null);
					}
					
					public CAstNode visitEventDefinition(JSONObject fd, Void ignore) {
						return visitCallableDefinition(fd, null);
					}
					
					public CAstNode visitErrorDefinition(JSONObject fd, Void ignore) {
						return visitCallableDefinition(fd, null);
					}

					@Override
					public CAstNode visitNode(JSONObject oo, Void ignore) {
						CAstNode ref = makeRef(o, getType(oo, context));
						context.getNodeTypeMap().add(ref, getType(oo, context));
						return ref;
					}
					
					private CAstNode makeRef(JSONObject obj, CAstType type) {
						CAstNode self = TranslationVisitor.this.visit(obj.getJSONObject("expression"), context);
						return record(
							ast.makeNode(CAstNode.OBJECT_REF, 
								self==null? ast.makeNode(CAstNode.EMPTY): self,
								ast.makeConstant(o.getString("memberName"))),
							getLocation(o.getString("src")), type, context);
					}
					
				}.visit(decl, null);
			}
			
			@SuppressWarnings("unused")
			public CAstNode visitModifierDefinition(JSONObject o, SolidityWalkContext context) {
			    FunctionEntity funEntity = (FunctionEntity) visitCallableDefinition(o, null, false, context);

			    SolidityWalkContext child = codeContext(funEntity, context);

			   visit(o.getJSONObject("body"), child);
			   
			   return ast.makeConstant(CAstNode.EMPTY);
			}

			@SuppressWarnings("unused")
			public CAstNode visitNewExpression(JSONObject o, SolidityWalkContext context) {
				CAstType type = getType(o.getJSONObject("typeName"), context);
				return record(ast.makeNode(CAstNode.NEW, ast.makeConstant(type)), getLocation(o.getString("src")), context);
			}

			@SuppressWarnings("unused")
			public CAstNode visitPlaceholderStatement(JSONObject o, SolidityWalkContext context) {
				return ast.makeNode(CAstNode.EMPTY);
			}

			@SuppressWarnings("unused")
			public CAstNode visitPragmaDirective(JSONObject o, SolidityWalkContext context) {
				return ast.makeNode(CAstNode.EMPTY);
			}
			
			@SuppressWarnings("unused")
			public CAstNode visitReturn(JSONObject o, SolidityWalkContext context) {
				CAstNode exiting = returnThroughExit(o, context);
				if (exiting != null) {
					return exiting;
				}
				if (o.has("expression")) {
					return record(ast.makeNode(CAstNode.RETURN, visit(o.getJSONObject("expression"), context)), getLocation(o.getString("src")), context);					
				} else {
					return record(ast.makeNode(CAstNode.RETURN), getLocation(o.getString("src")), context);					
				}
			}

			/**
			 * A {@code return} inside a value-returning function, as assignments to the return
			 * variables followed by a jump to the function's exit. Returns null when there is no such
			 * exit (a void function), leaving the statement to translate as an ordinary return.
			 */
			private CAstNode returnThroughExit(JSONObject o, SolidityWalkContext context) {
				ReturnTarget target = returnTargets.peek();
				if (target == null) {
					return null;
				}
				List<CAstNode> stmts = new ArrayList<>();
				if (o.has("expression")) {
					JSONObject value = o.getJSONObject("expression");
					if (target.names.size() == 1) {
						stmts.add(ast.makeNode(CAstNode.ASSIGN,
							ast.makeNode(CAstNode.VAR, ast.makeConstant(target.names.get(0))),
							visit(value, context)));
					} else {
						// destructure, so every returned value is computed before any return variable
						// is written ("return (y, x)" swaps) and a tuple-valued call is split too
						stmts.add(ast.makeNode(CAstNode.ASSIGN,
							ast.makeNode(CAstNode.NEW, ast.makeConstant(target.type),
								target.names.stream()
									.map(name -> ast.makeNode(CAstNode.VAR, ast.makeConstant(name)))
									.toArray(i -> new CAstNode[i])),
							visit(value, context)));
					}
				}
				CAstNode goExit = ast.makeNode(CAstNode.GOTO);
				context.cfg().map(goExit, goExit);
				context.cfg().add(goExit, target.exit, null);
				stmts.add(goExit);
				return record(ast.makeNode(CAstNode.BLOCK_STMT, stmts), getLocation(o.getString("src")), context);
			}

			@SuppressWarnings("unused")
			public CAstNode visitRevertStatement(JSONObject o, SolidityWalkContext context) {
				return record(ast.makeNode(CAstNode.THROW, visit(o.getJSONObject("errorCall"), context)), getLocation(o.getString("src")), context);					
			}
				
			@SuppressWarnings("unused")
			public CAstNode visitSourceUnit(JSONObject o, SolidityWalkContext context) {
				Map<String,JSONObject> renamings = HashMapFactory.make();
				SolidityWalkContext aliases = new SolidityWalkContext() {
					@Override
					public WalkContext<SolidityWalkContext, JSONObject> getParent() {
						return context;
					}

					@Override
					public Map<String, JSONObject> renamings() {
						return renamings;
					}
				};
				return ast.makeNode(CAstNode.BLOCK_STMT, Streams.stream(o.getJSONArray("nodes").iterator())
						.map(n -> visit((JSONObject) n, aliases)).toList());
			}

			@SuppressWarnings("unused")
			public CAstNode visitStructDefinition(JSONObject o, SolidityWalkContext context) {
				ids.put(o.getInt("id"), o);
				SolidityWalkContext child = structContainerContext(o, context);

				o.getJSONArray("members").forEach(m -> {
					visit((JSONObject) m, child);
				});

				StructType structType = (StructType) getType(o, context);

				Set<CAstEntity> elts = child.variables().stream().collect(Collectors.toSet());

				ContractEntity structEntity = new ContractEntity(structType, getLocation(o.getString("src")), null,
						elts);

				context.addScopedEntity(null, structEntity);

				return ast.makeNode(CAstNode.EMPTY);
			}

			@SuppressWarnings("unused")
			public CAstNode visitTryStatement(JSONObject o, SolidityWalkContext context) {
				return visit(o.getJSONObject("externalCall"), context);
			}
			
			@SuppressWarnings("unused")
			public CAstNode visitTupleExpression(JSONObject o, SolidityWalkContext context) {
				JSONArray components = o.getJSONArray("components");
				if (components.length() == 1) {
					return visit(components.getJSONObject(0), context);
				} else {
					CAstType tt = SolidityTupleType.get(Streams.stream(components.iterator())
						.map(x -> x instanceof JSONObject? getType((JSONObject)x, context): SolidityCAstType.get("void"))
						.toArray(i -> new CAstType[i]));
					CAstNode[] args = Streams.stream(components.iterator())
						.map(x -> x instanceof JSONObject? visit((JSONObject)x, context): ast.makeNode(CAstNode.EMPTY))
						.toArray(i -> new CAstNode[i]);
					return record(ast.makeNode(CAstNode.NEW,
						ast.makeConstant(tt),
						args), getLocation(o.getString("src")), tt, context);
				}
			}
			
			@SuppressWarnings("unused")
			public CAstNode visitUnaryOperation(JSONObject o, SolidityWalkContext context) {
				CAstNode operand = visit(o.getJSONObject("subExpression"), context);

				String opName = o.getString("operator");
				CAstOperator op = translateOpcode(opName);

				CAstNode expr;
				if ("++".equals(opName) || "--".equals(opName)) {
					expr = ast.makeNode((o.getBoolean("prefix") ? CAstNode.ASSIGN_PRE_OP : CAstNode.ASSIGN_POST_OP),
							operand, ast.makeConstant(1), op);
				} else if ("delete".equals(opName)) {
					expr = ast.makeNode(CAstNode.PRIMITIVE, ast.makeConstant("delete"), operand);
				} else {
					expr = ast.makeNode(CAstNode.UNARY_EXPR, op, operand);
				}

				record(expr, getLocation(o.getString("src")), getType(o, context), context);
				return expr;
			}

			@SuppressWarnings("unused")
			public CAstNode visitUserDefinedValueTypeDefinition(JSONObject o, SolidityWalkContext context) {
				CAstType realType = getType(o.getJSONObject("underlyingType"), context);
				SolidityCAstType.record(o.getString("name"), realType, SolidityCAstType.getIRType(realType));
				return ast.makeNode(CAstNode.EMPTY);
			}
			
			@SuppressWarnings("unused")
			public CAstNode visitUsingForDirective(JSONObject o, SolidityWalkContext context) {
				return ast.makeNode(CAstNode.EMPTY);
			}

			private boolean isStructField(JSONObject decl, SolidityWalkContext context) {
				return context.contract() != null &&  context.contract().getString("nodeType").equals("StructDefinition");
			}

			@SuppressWarnings("unused")
			public CAstNode visitVariableDeclaration(JSONObject o, SolidityWalkContext context) {
				ids.put(o.getInt("id"), o);
				Position pos = getLocation(o.getString("src"));
				CAstType type = getType(o.getJSONObject("typeName"), context);
				boolean isFinal = o.has("constant") && o.getBoolean("constant");
				String name = o.getString("name");

				if (isStructField(o, context) || isContractField(o)) {
					CAstEntity f = new AbstractFieldEntity(name, type,
							isFinal ? Collections.singleton(CAstQualifier.FINAL) : Collections.emptySet(), false, pos,
							null);
					context.registerVariable(name, f);
					return ast.makeNode(CAstNode.EMPTY);
				} else {
					CAstNode v = o.has("initialValue") ? visit(o.getJSONObject("initialValue"), context) : null;
					CAstSymbol symbol = new CAstSymbolImpl(name, type, isFinal);
					CAstNode result = ast.makeNode(CAstNode.DECL_STMT, ast.makeConstant(symbol));
					record(result, pos, type, context);
					return result;
				}
			}

			@SuppressWarnings("unused")
			public CAstNode visitVariableDeclarationStatement(JSONObject o, SolidityWalkContext context) {
				List<CAstNode> decls = Streams.stream(o.getJSONArray("declarations").iterator())
						.map(d -> d instanceof JSONObject? visit((JSONObject) d, context): ast.makeNode(CAstNode.EMPTY) ).toList();

				decls = new ArrayList<>(decls);
				if (o.has("initialValue")) {
					if (decls.size() > 1) {
						String name = "decl" + idx++;
						decls.add(ast.makeNode(CAstNode.DECL_STMT,
								ast.makeConstant(new CAstSymbolImpl(name, CAstType.DYNAMIC))));
						decls.add(ast.makeNode(CAstNode.ASSIGN, ast.makeNode(CAstNode.VAR, ast.makeConstant(name)),
								visit(o.getJSONObject("initialValue"), context)));
						decls.add(ast.makeNode(CAstNode.ASSIGN,
								ast.makeNode(CAstNode.NEW,
										ast.makeConstant(getTupleType(Streams.stream(o.getJSONArray("declarations").iterator())
												.map(x -> x instanceof JSONObject? ((JSONObject)x).getJSONObject("typeName"): null).toList(), context)),
										Streams.stream(o.getJSONArray("declarations").iterator())
										.map(d -> !(d instanceof JSONObject) ? ast.makeNode(CAstNode.EMPTY)
												: ast.makeNode(CAstNode.VAR,
														ast.makeConstant(((JSONObject) d).getString("name"))))
										.toArray(i -> new CAstNode[i])),
								ast.makeNode(CAstNode.VAR, ast.makeConstant(name))));
					} else {
						JSONObject d = (JSONObject) o.getJSONArray("declarations").iterator().next();
						decls.add(ast.makeNode(CAstNode.ASSIGN, 
								ast.makeNode(CAstNode.VAR, ast.makeConstant(d.getString("name"))),
								visit(o.getJSONObject("initialValue"), context)));
					}
				}

				return ast.makeNode(CAstNode.BLOCK_STMT, decls);
			}

			@SuppressWarnings("unused")
			public CAstNode visitWhileStatement(JSONObject o, SolidityWalkContext context) {
				CAstNode test = visit(o.getJSONObject("condition"), context);
				
				JSONObject contLabel = JSONObject.fromJson("{\"nodeType\": \"Continue\"}", JSONObject.class);
				JSONObject breakLabel = JSONObject.fromJson("{\"nodeType\": \"Break\"}", JSONObject.class);
				SolidityLoopContext lc = new SolidityLoopContext(context, breakLabel, contLabel);
				CAstNode body = visit(o.getJSONObject("body"), lc);
				
				if (lc.continued) {
					CAstNode cs = ast.makeNode(CAstNode.LABEL_STMT, ast.makeConstant("cont" + idx++), ast.makeNode(CAstNode.EMPTY));
					context.cfg().map(contLabel, cs);
					body = record(ast.makeNode(CAstNode.BLOCK_STMT, body, cs), getLocation(o.getString("src")), context);
				}
				
				CAstNode result = 
					record(ast.makeNode(CAstNode.LOOP, 
						test,
						body), getLocation(o.getString("src")), context);
				
				if (lc.broke) {
					CAstNode bs = ast.makeNode(CAstNode.LABEL_STMT, ast.makeConstant("break" + idx++), ast.makeNode(CAstNode.EMPTY));
					context.cfg().map(breakLabel, bs);
					result = record(ast.makeNode(CAstNode.BLOCK_STMT, result, bs), getLocation(o.getString("src")), context);
				}
				
				return result;
			}

			/**
			 * {@code do B while (C)} has no CAst loop form (LOOP is test-first), so it is lowered with
			 * WALA's {@link TranslatorToCAst.DoLoopTranslator} in replicating mode, as the Java front end
			 * does: {@code B; while (C) B'} with B' a clone of B. Peeling the first iteration keeps the
			 * loop a test-first LOOP, which is the shape the downstream loop analyses expect. The
			 * continue and break labels are bound before cloning so that jumps in the clone resolve to
			 * the clone's continue label and the shared break label.
			 */
			@SuppressWarnings("unused")
			public CAstNode visitDoWhileStatement(JSONObject o, SolidityWalkContext context) {
				JSONObject contLabel = JSONObject.fromJson("{\"nodeType\": \"Continue\"}", JSONObject.class);
				JSONObject breakLabel = JSONObject.fromJson("{\"nodeType\": \"Break\"}", JSONObject.class);
				SolidityLoopContext lc = new SolidityLoopContext(context, breakLabel, contLabel);
				CAstNode body = visit(o.getJSONObject("body"), lc);
				CAstNode test = visit(o.getJSONObject("condition"), context);

				CAstNode cs = ast.makeNode(CAstNode.LABEL_STMT, ast.makeConstant("cont" + idx++), ast.makeNode(CAstNode.EMPTY));
				context.cfg().map(contLabel, cs);
				CAstNode bs = ast.makeNode(CAstNode.LABEL_STMT, ast.makeConstant("break" + idx++), ast.makeNode(CAstNode.EMPTY));
				context.cfg().map(breakLabel, bs);

				return record(new TranslatorToCAst.DoLoopTranslator(true, ast).translateDoLoop(test, body, cs, bs, context),
						getLocation(o.getString("src")), context);
			}

			@SuppressWarnings("unused")
			public CAstNode visitInlineAssembly(JSONObject o, SolidityWalkContext context) {
				JSONObject yulAst = o.getJSONObject("AST");

				Map<String, JSONObject> info = HashMapFactory.make();
				Streams.stream(o.getJSONArray("externalReferences").iterator()).forEach(e -> {
					JSONObject ej = (JSONObject) e;
					info.put(ej.getString("src"), ej);
				});

				class YulExpressionVisitor implements JsonNodeTypeOnlyVisitor<CAstNode> {
					public CAstNode visitYulLiteral(JSONObject o, Void ignore) {
						String v = o.getString("value");
						switch (o.getString("kind")) {
						case "string": return record(ast.makeConstant(v), getLocation(o.getString("src")), context);
						case "bool": return record(ast.makeConstant(o.getBoolean("value")), getLocation(o.getString("src")), context);
						case "number": 
							Number n;
							try {
								int radix = 10;
								String biv = v;
								if (biv.startsWith("0x")) {
									biv = biv.substring(2);
									radix = 16;
								}
								BigInteger i = new BigInteger(biv, radix);
							try {
								n = Integer.valueOf(i.intValueExact());
							} catch (ArithmeticException e) {
								try {
									n = Long.valueOf(i.longValueExact());						
								} catch (ArithmeticException e1) {
									n = i;
								}
							}
							} catch (NumberFormatException e) {
								n = Integer.decode(v);
							}
							return record(ast.makeConstant(n), getLocation(o.getString("src")), context);
						default:
							return ast.makeNode(CAstNode.EMPTY);
						}
					}
					
					public CAstNode visitYulIdentifier(JSONObject o, Void ignore) {
						String src = o.getString("src");
						String name = o.getString("name");
						if (info.containsKey(src)) {
							JSONObject decl = ids.get(info.get(src).getInt("declaration"));
							return handleIdentifierDeclaration(decl, getLocation(src), context);
						} else {
							int dot = name.indexOf('.');
							if (dot >= 0) {
								String obj = name.substring(0, dot);
								String field = name.substring(dot + 1);
								return 
									ast.makeNode(CAstNode.OBJECT_REF,
										ast.makeNode(CAstNode.VAR, ast.makeConstant(obj)), ast.makeConstant(field));
							} else {
								return ast.makeNode(CAstNode.VAR, ast.makeConstant(name));
							}
						}
					}
					
					public CAstNode visitYulFunctionCall(JSONObject o, Void ignore) {
						JSONObject fun = o.getJSONObject("functionName");
						JSONArray args = o.getJSONArray("arguments");
						CAstNode[] as = Streams.stream(args.iterator()).map(v -> (JSONObject)v).map(v -> visit(v, null)).toArray(i -> new CAstNode[i]);
						if (!"YulIdentifier".equals(fun.getString("nodeType")) || info.containsKey(fun.getString("src"))) {
							CAstNode callee = visit(fun, null);
							if (callee != null) {
								return ast.makeNode(CAstNode.CALL, callee, as);
							} else {
								return ast.makeNode(CAstNode.EMPTY);
							}
						} else {
							switch (fun.getString("name")) {
							case "add":
		                      return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_ADD, as[0], as[1]), getLocation(o.getString("src")), context);
							case "div":
			                      return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_DIV, as[0], as[1]), getLocation(o.getString("src")), context);
							case "eq":
			                      return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_EQ, as[0], as[1]), getLocation(o.getString("src")), context);
							case "mul":
			                      return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_MUL, as[0], as[1]), getLocation(o.getString("src")), context);
							case "mod":
			                      return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_MOD, as[0], as[1]), getLocation(o.getString("src")), context);
							case "or":
			                      return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_BIT_OR, as[0], as[1]), getLocation(o.getString("src")), context);
							case "and":
			                      return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_BIT_AND, as[0], as[1]), getLocation(o.getString("src")), context);
							case "shr":
								  // Yul shr(s, v) is v >> s: the shift amount comes first
			                      return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_RSH, as[1], as[0]), getLocation(o.getString("src")), context);
							case "sub":
			                      return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_SUB, as[0], as[1]), getLocation(o.getString("src")), context);
							case "gt":
		                        return ast.makeNode(CAstNode.IF_EXPR, record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_GT, as[0], as[1]), getLocation(o.getString("src")), context), ast.makeConstant(1), ast.makeConstant(0));
							case "sgt": // signed comparison; signedness is not modelled downstream
		                        return ast.makeNode(CAstNode.IF_EXPR, record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_GT, as[0], as[1]), getLocation(o.getString("src")), context), ast.makeConstant(1), ast.makeConstant(0));
							case "iszero":
		                        return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_EQ, as[0], ast.makeConstant(0)), getLocation(o.getString("src")), context);
							case "not": // bitwise complement, not logical negation
		                        return record(ast.makeNode(CAstNode.UNARY_EXPR, CAstOperator.OP_BITNOT, as[0]), getLocation(o.getString("src")), context);
							case "lt":
		                        return ast.makeNode(CAstNode.IF_EXPR, record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_LT, as[0], as[1]), getLocation(o.getString("src")), context), ast.makeConstant(1), ast.makeConstant(0));
							case "slt": // signed comparison; signedness is not modelled downstream
		                        return ast.makeNode(CAstNode.IF_EXPR, record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_LT, as[0], as[1]), getLocation(o.getString("src")), context), ast.makeConstant(1), ast.makeConstant(0));
							case "shl": // Yul shl(s, v) is v << s: the shift amount comes first
		                        return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_LSH, as[1], as[0]), getLocation(o.getString("src")), context);
							case "sar": // signed shift right; Yul sar(s, v) is v >> s
		                        return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_RSH, as[1], as[0]), getLocation(o.getString("src")), context);
							case "xor":
		                        return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_BIT_XOR, as[0], as[1]), getLocation(o.getString("src")), context);
							case "exp":
		                        return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_POW, as[0], as[1]), getLocation(o.getString("src")), context);
							case "sdiv": // signed division; signedness is not modelled downstream
		                        return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_DIV, as[0], as[1]), getLocation(o.getString("src")), context);
							case "smod": // signed remainder; signedness is not modelled downstream
		                        return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_MOD, as[0], as[1]), getLocation(o.getString("src")), context);
							case "mulmod":
		                        return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_MOD, ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_MUL, as[0], as[1]), as[2]), getLocation(o.getString("src")), context);
							case "addmod":
		                        return record(ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_MOD, ast.makeNode(CAstNode.BINARY_EXPR, CAstOperator.OP_ADD, as[0], as[1]), as[2]), getLocation(o.getString("src")), context);
		                    default:
		                    	// Dataflow through the builtin is severed: the EMPTY node becomes a
		                    	// null constant. Say so instead of doing it silently.
		                    	System.err.println("warning: unhandled Yul builtin '" + fun.getString("name")
		                    			+ "' at " + o.getString("src") + "; its result is opaque");
		                    	return ast.makeNode(CAstNode.EMPTY);
		                   }
						}
					}
				}

				class YulStatementVisitor implements JsonNodeTypeOnlyVisitor<CAstNode> {
					public CAstNode visitYulExpressionStatement(JSONObject o, Void ignore) {
						return new YulExpressionVisitor().visit(o.getJSONObject("expression"), null);
					}

					public CAstNode visitYulSwitch(JSONObject o, Void ignore) {
						return ast.makeNode(CAstNode.EMPTY);
					}
					
					public CAstNode visitYulIf(JSONObject o, Void ignore) {
						CAstNode cond = new YulExpressionVisitor().visit(o.getJSONObject("condition"), null);
						CAstNode then = visit(o.getJSONObject("body"), null);
						return record(ast.makeNode(CAstNode.IF_STMT, cond, then), getLocation(o.getString("src")), context);
					}
					
					public CAstNode visitYulBlock(JSONObject o, Void ignore) {
						return ast.makeNode(CAstNode.BLOCK_STMT, Streams.stream(o.getJSONArray("statements").iterator())
								.map(e -> visit((JSONObject) e, null)).toList());
					}

					public CAstNode visitYulVariableDeclaration(JSONObject o, Void ignore) {
						List<CAstNode> decls = Streams.stream(o.getJSONArray("variables").iterator())
							.map(v -> ast.makeNode(CAstNode.DECL_STMT,
									    ast.makeConstant(
									    	new CAstSymbolImpl(((JSONObject)v).getString("name"), CAstType.DYNAMIC))))
							.toList();
					
						CAstNode val = o.has("value")? new YulExpressionVisitor().visit(o.getJSONObject("value"), null): null;
						
						if (decls.size() == 1) {
							return ast.makeNode(CAstNode.BLOCK_STMT, 
								ast.makeNode(CAstNode.BLOCK_STMT, decls),
								(val != null)?
								ast.makeNode(CAstNode.ASSIGN,
									ast.makeNode(CAstNode.VAR, ast.makeConstant(o.getJSONArray("variables").getJSONObject(0).getString("name"))),
									val):
								ast.makeNode(CAstNode.EMPTY));
									
						} else {
							return ast.makeNode(CAstNode.EMPTY);
						}
					}
					
					public CAstNode visitYulAssignment(JSONObject o, Void ignore) {
						CAstNode val = new YulExpressionVisitor().visit(o.getJSONObject("value"), null);

						JSONArray varNames = o.getJSONArray("variableNames");
						if (varNames.length() == 1) {
							return record(ast.makeNode(CAstNode.ASSIGN,
									new YulExpressionVisitor().visit(varNames.getJSONObject(0), null),
									val),
									getLocation(o.getString("src")), context);
						} else {
							return ast.makeNode(CAstNode.EMPTY);
						}
					}
					
					public CAstNode visitYulForLoop(JSONObject o, Void ignore) {
						return ast.makeNode(CAstNode.EMPTY);					
					}

					public CAstNode visitYulFunctionDefinition(JSONObject o, Void ignore) {
						return ast.makeNode(CAstNode.EMPTY);					
					}

				}

				return new YulStatementVisitor().visit(yulAst, null);
			}
		}

		public SolidityJSONTranslator(JSONObject tree) {
			this.tree = tree;
		}

		private final JSONObject tree;
		private File sourceFile;
		private int[] linePositionMap;
		private byte[] rawLines;
		
		private Reader getReader() throws FileNotFoundException {
			return new InputStreamReader(new ByteArrayInputStream(rawLines));
		}

		@Override
		public <C extends RewriteContext<K>, K extends CopyKey<K>> void addRewriter(CAstRewriterFactory<C, K> factory,
				boolean prepend) {
			// TODO Auto-generated method stub
		}

		@Override
		public CAstEntity translateToCAst() throws Error, IOException {
			sourceFile = new File(tree.getString("absolutePath"));
			rawLines = Files.readAllBytes(Paths.get(sourceFile.toURI()));
			for(int i = 0; i < rawLines.length; ) {
				int b = rawLines[i] & 0xFF;
				if (b >= 0xF0) {
					for(int j = 0; j < 4; j++) {
						rawLines[i+j] = '?';
					}
					i += 4;
				} else if (b >= 0xE0) {
					for(int j = 0; j < 3; j++) {
						rawLines[i+j] = '?';
					}
					i += 3;
				}  else if (b >= 0xC0) {
					for(int j = 0; j < 2; j++) {
						rawLines[i+j] = '?';
					}
					i += 2;
				} else {
					assert b <= 0x7F;
					i++;
				}
			}
			String[] lines = new String(rawLines).split("\n");
			//String[] lines = Files.readAllLines (Paths.get(sourceFile.toURI())).toArray(i -> new String[i]);
			for(int i = 0; i < lines.length; i++) {
				lines[i] = new String(lines[i].getBytes("ASCII"));
			}
			linePositionMap = new int[lines.length + 1];
			int total = 0;
			for (int i = 0; i < lines.length; i++) {
				linePositionMap[i + 1] = total;
				total += (lines[i].length() + 1);
			}
						
			final AbstractEntity fileEntity = new AbstractEntity() {
				CAstSourcePositionRecorder rec = new CAstSourcePositionRecorder();
				CAstNodeTypeMapRecorder types = new CAstNodeTypeMapRecorder();
				
				@Override
				public int getKind() {
					return CAstEntity.FILE_ENTITY;
				}

				@Override
				public String getName() {
					return tree.getString("absolutePath");
				}

				@Override
				public CAstNode getAST() {
					assert false;
					return null;
				}

				@Override
				public int getArgumentCount() {
					assert false;
					return 0;
				}

				@Override
				public CAstNode[] getArgumentDefaults() {
					assert false;
					return null;
				}

				@Override
				public String[] getArgumentNames() {
					return new String[0];
				}

				@Override
				public CAstControlFlowMap getControlFlow() {
					assert false;
					return null;
				}

				@Override
				public Position getNamePosition() {
					assert false;
					return null;
				}

				@Override
				public CAstNodeTypeMapRecorder getNodeTypeMap() {
					return types;
				}

				@Override
				public Position getPosition(int arg) {
					assert false;
					return null;
				}

				@Override
				public Collection<CAstQualifier> getQualifiers() {
					assert false;
					return null;
				}

				@Override
				public CAstSourcePositionRecorder getSourceMap() {
					return rec;
				}

				@Override
				public CAstType getType() {
					assert false;
					return null;
				}

				@Override
				public String toString() {
					return "<file " + getName() + ">";
				}
			};

			class RootContext implements FunctionContainerContext, VariableContainerContext {
				@Override
				public WalkContext<SolidityWalkContext, JSONObject> getParent() {
					return null;
				} 
				
				@Override
				public JSONObject contract() {
					return null;
				}

				public String toString() {
					return "root context for " + sourceFile;
				}

				@Override
				public void addScopedEntity(CAstNode newNode, CAstEntity visit) {
					fileEntity.addScopedEntity(newNode, visit);
				}
		
				private final Collection<CAstEntity> vars = new ArrayList<>();
				private final Collection<CAstEntity> functions = new ArrayList<>();

				@Override
				public Collection<CAstEntity> variables() {
					return vars;
				}

				@Override
				public Collection<CAstEntity> functions() {
					return functions;
				}

				@Override
				public JSONObject sourceUnit() {
					return tree;
				}

				@Override
				public CAstNodeTypeMapRecorder getNodeTypeMap() {
					return (CAstNodeTypeMapRecorder) fileEntity.getNodeTypeMap();
				}

				@Override
				public CAstSourcePositionRecorder pos() {
					return (CAstSourcePositionRecorder) fileEntity.getSourceMap();
				}
			}

			RootContext context = new RootContext();
			
			new TranslationVisitor().visit(tree, context);

			context.functions.forEach(e -> fileEntity.addScopedEntity(null, e));
			context.vars.forEach(e -> fileEntity.addScopedEntity(null, e));
			
			return fileEntity;
		}
	}

	private final Map<Integer, JSONObject> ids = HashMapFactory.make();

	private boolean isContractField(JSONObject decl) {
		return decl.has("stateVariable") && decl.getBoolean("stateVariable");
	}
	
	public class DeclarationFinder implements JsonByNodeVisitor {
		private final int id;
		private final String name;
		private JSONObject decl;

		DeclarationFinder(int id, String name) {
			this.id = id;
			this.name = name;
		}
		
		public Void check(JSONObject o) {
			if (id == o.getInt("id") && (name == null || (o.has("name") && o.getString("name").equals(name)))) {
				decl = o;
			} else {
				visitNode(o, null);
			}
			return null;
		}

		
		public Void visitUserDefinedValueTypeDefinition(JSONObject o, Void context) {
			if (id == o.getInt("id") && (name == null || (o.has("name") && o.getString("name").equals(name)))) {
				decl = o;
			} else {
				visitNode(o, null);
			}
			return null;
		}

		public Void visitFunctionDefinition(JSONObject o, Void context) {
			return check(o);
		}

		public Void visitEnumDefinition(JSONObject o, Void context) {
			return check(o);
		}

		public Void visitErrorDefinition(JSONObject o, Void context) {
			return check(o);
		}

		public Void visitEnumValue(JSONObject o, Void context) {
			return check(o);
		}

		public Void visitContractDefinition(JSONObject o, Void context) {
			return check(o);
		}

		public Void visitStructDefinition(JSONObject o, Void context) {
			return check(o);
		}

		public Void visitEventDefinition(JSONObject o, Void context) {
			return check(o);
		}

		public Void visitImportDirective(JSONObject o, Void context) {
			if (name != null && name.equals(o.get("unitAlias"))) {
				return new DeclarationFinder(id, null).visit(loader.getSource(o.getInt("sourceUnit")), null);
			} else {
				return visitNode(o, null);
			}
		}

		public Void visitVariableDeclaration(JSONObject o, Void context) {
			if (isContractField(o)) {
				return check(o);
			} else {
				return null;
			}
		}
	}

	private JSONObject getDeclaration(JSONObject o, SolidityWalkContext context) {
		if (o.has("referencedDeclaration")) {
			return getDeclaration(o.getInt("referencedDeclaration"), o.has("name")? o.getString("name"): null, context);
		} else {
			return null;
		}
	}
	
	private boolean compareNames(String s1, String s2) {
		if (s1.contains(".")) {
			s1 = s1.substring(s1.lastIndexOf('.')+1);
		}
		if (s2.contains(".")) {
			s2 = s2.substring(s2.lastIndexOf('.')+1);
		}
		return s1.equals(s2);
	}
	
	private JSONObject getDeclaration(int decl, String name, SolidityWalkContext context) {
		if (ids.containsKey(decl) && (name == null || compareNames(name, ids.get(decl).getString("name")))) {
			return ids.get(decl);			
		} else {
			if (name != null && context.renamings().containsKey(name)) {
				ids.put(decl, context.renamings().get(name));
				return ids.get(decl);	
			} else {
				JSONObject su = context.sourceUnit();
				DeclarationFinder df = new DeclarationFinder(decl, name);
				df.visit(su, null);
				if (df.decl != null) {
					ids.put(decl, df.decl);
					return df.decl;
				} else {
					MutableIntSet done = IntSetUtil.make();
					ImportVisitor imports = new ImportVisitor();
					imports.visit(su, null);
					IntSet imported = imports.imports;
					do {
						MutableIntSet next = IntSetUtil.make();
						IntIterator sus = imported.intIterator();
						while (sus.hasNext()) {
							int importedID = sus.next();
							JSONObject importedSU = loader.getSource(importedID);
							if (imports.aliased.contains(importedID) && name != null) {
								DeclarationFinder adf = new DeclarationFinder(decl, null);
								adf.visit(importedSU, null);
								if (adf.decl != null) {
									ids.put(decl, adf.decl);
									return adf.decl;
								}													
							} else {
								df.visit(importedSU, null);
								if (df.decl != null) {
									ids.put(decl, df.decl);
									return df.decl;
								}				
							}
							imports.visit(importedSU, null);
							next.addAll(imports.imports);
						}
						done.addAll(imported);
						imported = IntSetUtil.diff(next, done);
					} while (imported.size() > 0);

					if (name != null) {
						df.visit(loader.getSources(), null);
						if (df.decl != null) {
							ids.put(decl, df.decl);
							return df.decl;					
						}
					}
				}
			}
		}
		return null;
	}

	public void translateFiles(String compilerOutputFile) throws Error, IOException {
		JSONTokener toks = new JSONTokener(new FileReader(compilerOutputFile));
		while (toks.more()) {
			Object tok;
			try {
				tok = toks.nextValue();
			} catch (JSONException e) {
				toks.next();
				continue;
			}

			if (tok instanceof JSONObject && ((JSONObject) tok).has("absolutePath")) {
				new SolidityJSONTranslator((JSONObject) tok).translateToCAst();
			}
		}
	}

}

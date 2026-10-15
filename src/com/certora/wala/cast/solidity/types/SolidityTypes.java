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
package com.certora.wala.cast.solidity.types;

import com.ibm.wala.core.util.strings.Atom;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;

public class SolidityTypes {

	public static Atom solidityLanguage = Atom.findOrCreateUnicodeAtom("Solidity");
	
	public static ClassLoaderReference solidity = new ClassLoaderReference(Atom.findOrCreateUnicodeAtom("solidity"), solidityLanguage, null);
	
	public static TypeReference root = TypeReference.findOrCreate(solidity, "Lroot");

	public static TypeReference error = TypeReference.findOrCreate(solidity, "Lerror");

	public static TypeReference contract = TypeReference.findOrCreate(solidity, "Lcontract");

	public static TypeReference msg = TypeReference.findOrCreate(solidity, "Lmsg");

	public static TypeReference codeBody = TypeReference.findOrCreate(solidity, "LCodeBody");

	public static TypeReference function = TypeReference.findOrCreate(solidity, "Lfunction");

	public static TypeReference event = TypeReference.findOrCreate(solidity, "Levent");

	public static TypeReference uint8 = TypeReference.findOrCreate(solidity, "Puint8");

	public static TypeReference uint24 = TypeReference.findOrCreate(solidity, "Puint24");

	public static TypeReference uint16 = TypeReference.findOrCreate(solidity, "Puint16");

	public static TypeReference uint32 = TypeReference.findOrCreate(solidity, "Puint32");

	public static TypeReference uint40 = TypeReference.findOrCreate(solidity, "Puint40");

	public static TypeReference uint48 = TypeReference.findOrCreate(solidity, "Puint48");

	public static TypeReference uint56 = TypeReference.findOrCreate(solidity, "Puint56");

	public static TypeReference uint64 = TypeReference.findOrCreate(solidity, "Puint64");

	public static TypeReference uint72 = TypeReference.findOrCreate(solidity, "Puint72");

	public static TypeReference uint80 = TypeReference.findOrCreate(solidity, "Puint80");

	public static TypeReference uint88 = TypeReference.findOrCreate(solidity, "Puint88");

	public static TypeReference uint96 = TypeReference.findOrCreate(solidity, "Puint96");

	public static TypeReference uint104 = TypeReference.findOrCreate(solidity, "Puint104");

	public static TypeReference uint112 = TypeReference.findOrCreate(solidity, "Puint112");

	public static TypeReference uint120 = TypeReference.findOrCreate(solidity, "Puint120");

	public static TypeReference uint128 = TypeReference.findOrCreate(solidity, "Puint128");

	public static TypeReference uint136 = TypeReference.findOrCreate(solidity, "Puint136");

	public static TypeReference uint144 = TypeReference.findOrCreate(solidity, "Puint144");

	public static TypeReference uint152 = TypeReference.findOrCreate(solidity, "Puint152");

	public static TypeReference uint160 = TypeReference.findOrCreate(solidity, "Puint160");

	public static TypeReference uint168 = TypeReference.findOrCreate(solidity, "Puint168");

	public static TypeReference uint176 = TypeReference.findOrCreate(solidity, "Puint176");

	public static TypeReference uint184 = TypeReference.findOrCreate(solidity, "Puint184");

	public static TypeReference uint192 = TypeReference.findOrCreate(solidity, "Puint192");

	public static TypeReference uint200 = TypeReference.findOrCreate(solidity, "Puint200");

	public static TypeReference uint208 = TypeReference.findOrCreate(solidity, "Puint208");

	public static TypeReference uint216 = TypeReference.findOrCreate(solidity, "Puint216");

	public static TypeReference uint224 = TypeReference.findOrCreate(solidity, "Puint224");

	public static TypeReference uint232 = TypeReference.findOrCreate(solidity, "Puint232");

	public static TypeReference uint240 = TypeReference.findOrCreate(solidity, "Puint240");

	public static TypeReference uint248 = TypeReference.findOrCreate(solidity, "Puint248");

	public static TypeReference uint256 = TypeReference.findOrCreate(solidity, "Puint256");

	public static TypeReference int8 = TypeReference.findOrCreate(solidity, "Pint8");

	public static TypeReference int24 = TypeReference.findOrCreate(solidity, "Pint24");

	public static TypeReference int16 = TypeReference.findOrCreate(solidity, "Pint16");

	public static TypeReference int32 = TypeReference.findOrCreate(solidity, "Pint32");

	public static TypeReference int40 = TypeReference.findOrCreate(solidity, "Pint40");

	public static TypeReference int48 = TypeReference.findOrCreate(solidity, "Pint48");

	public static TypeReference int56 = TypeReference.findOrCreate(solidity, "Pint56");

	public static TypeReference int64 = TypeReference.findOrCreate(solidity, "Pint64");

	public static TypeReference int72 = TypeReference.findOrCreate(solidity, "Pint72");

	public static TypeReference int80 = TypeReference.findOrCreate(solidity, "Pint80");

	public static TypeReference int88 = TypeReference.findOrCreate(solidity, "Pint88");

	public static TypeReference int96 = TypeReference.findOrCreate(solidity, "Pint96");

	public static TypeReference int104 = TypeReference.findOrCreate(solidity, "Pint104");

	public static TypeReference int112 = TypeReference.findOrCreate(solidity, "Pint112");

	public static TypeReference int120 = TypeReference.findOrCreate(solidity, "Pint120");

	public static TypeReference int128 = TypeReference.findOrCreate(solidity, "Pint128");

	public static TypeReference int136 = TypeReference.findOrCreate(solidity, "Pint136");

	public static TypeReference int144 = TypeReference.findOrCreate(solidity, "Pint144");

	public static TypeReference int152 = TypeReference.findOrCreate(solidity, "Pint152");

	public static TypeReference int160 = TypeReference.findOrCreate(solidity, "Pint160");

	public static TypeReference int168 = TypeReference.findOrCreate(solidity, "Pint168");

	public static TypeReference int176 = TypeReference.findOrCreate(solidity, "Pint176");

	public static TypeReference int184 = TypeReference.findOrCreate(solidity, "Pint184");

	public static TypeReference int192 = TypeReference.findOrCreate(solidity, "Pint192");

	public static TypeReference int200 = TypeReference.findOrCreate(solidity, "Pint200");

	public static TypeReference int208 = TypeReference.findOrCreate(solidity, "Pint208");

	public static TypeReference int216 = TypeReference.findOrCreate(solidity, "Pint216");

	public static TypeReference int224 = TypeReference.findOrCreate(solidity, "Pint224");

	public static TypeReference int232 = TypeReference.findOrCreate(solidity, "Pint232");

	public static TypeReference int240 = TypeReference.findOrCreate(solidity, "Pint240");

	public static TypeReference int248 = TypeReference.findOrCreate(solidity, "Pint248");

	public static TypeReference int256 = TypeReference.findOrCreate(solidity, "Pint256");
	
	public static TypeReference address = TypeReference.findOrCreate(solidity, "Laddress");

	public static TypeReference string = TypeReference.findOrCreate(solidity, "Pstring");

	public static TypeReference bool = TypeReference.findOrCreate(solidity, "Pbool");

	public static TypeReference struct = TypeReference.findOrCreate(solidity, "Lstruct");

	public static TypeReference interfce = TypeReference.findOrCreate(solidity, "Linterface");

	public static TypeReference library = TypeReference.findOrCreate(solidity, "Llibrary");

	public static TypeReference bytes1 = TypeReference.findOrCreate(solidity, "Pbytes1");

	public static TypeReference bytes2 = TypeReference.findOrCreate(solidity, "Pbytes2");

	public static TypeReference bytes3 = TypeReference.findOrCreate(solidity, "Pbytes3");

	public static TypeReference bytes4 = TypeReference.findOrCreate(solidity, "Pbytes4");

	public static TypeReference bytes5 = TypeReference.findOrCreate(solidity, "Pbytes5");

	public static TypeReference bytes6 = TypeReference.findOrCreate(solidity, "Pbytes6");

	public static TypeReference bytes7 = TypeReference.findOrCreate(solidity, "Pbytes7");

	public static TypeReference bytes8 = TypeReference.findOrCreate(solidity, "Pbytes8");

	public static TypeReference bytes9 = TypeReference.findOrCreate(solidity, "Pbytes9");

	public static TypeReference bytes10 = TypeReference.findOrCreate(solidity, "Pbytes10");

	public static TypeReference bytes11 = TypeReference.findOrCreate(solidity, "Pbytes11");

	public static TypeReference bytes12 = TypeReference.findOrCreate(solidity, "Pbytes12");

	public static TypeReference bytes13 = TypeReference.findOrCreate(solidity, "Pbytes13");

	public static TypeReference bytes14 = TypeReference.findOrCreate(solidity, "Pbytes14");

	public static TypeReference bytes15 = TypeReference.findOrCreate(solidity, "Pbytes15");

	public static TypeReference bytes16 = TypeReference.findOrCreate(solidity, "Pbytes16");

	public static TypeReference bytes17 = TypeReference.findOrCreate(solidity, "Pbytes17");

	public static TypeReference bytes18 = TypeReference.findOrCreate(solidity, "Pbytes18");

	public static TypeReference bytes19 = TypeReference.findOrCreate(solidity, "Pbytes19");

	public static TypeReference bytes20 = TypeReference.findOrCreate(solidity, "Pbytes20");

	public static TypeReference bytes21 = TypeReference.findOrCreate(solidity, "Pbytes21");

	public static TypeReference bytes22 = TypeReference.findOrCreate(solidity, "Pbytes22");

	public static TypeReference bytes23 = TypeReference.findOrCreate(solidity, "Pbytes23");

	public static TypeReference bytes24 = TypeReference.findOrCreate(solidity, "Pbytes24");

	public static TypeReference bytes25 = TypeReference.findOrCreate(solidity, "Pbytes25");

	public static TypeReference bytes26 = TypeReference.findOrCreate(solidity, "Pbytes26");

	public static TypeReference bytes27 = TypeReference.findOrCreate(solidity, "Pbytes27");

	public static TypeReference bytes28 = TypeReference.findOrCreate(solidity, "Pbytes28");

	public static TypeReference bytes29 = TypeReference.findOrCreate(solidity, "Pbytes29");

	public static TypeReference bytes30 = TypeReference.findOrCreate(solidity, "Pbytes30");

	public static TypeReference bytes31 = TypeReference.findOrCreate(solidity, "Pbytes31");

	public static TypeReference bytes32 = TypeReference.findOrCreate(solidity, "Pbytes32");

	public static TypeReference bytes = TypeReference.findOrCreate(solidity, "Pbytes");

	public static TypeReference enm = TypeReference.findOrCreate(solidity, "Lenum");

	public static TypeReference abi = TypeReference.findOrCreate(solidity, "Labi");

	public static TypeReference block = TypeReference.findOrCreate(solidity, "Lblock");

	public static TypeReference tuple = TypeReference.findOrCreate(solidity, "Ltuple");

	public static TypeReference mapping = TypeReference.findOrCreate(solidity, "Lmapping");

}

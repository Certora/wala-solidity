/*
 * Copyright (c) 2024 Certora
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package com.certora.wala.analysis.rounding;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

import com.ibm.wala.cast.loader.AstMethod;
import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.shrike.shrikeBT.IBinaryOpInstruction;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.util.collections.HashMapFactory;

/**
 * E-graph prototype, step 1: writes the arithmetic around each division of a call-graph node as
 * egglog facts, together with what phase 1 decides for that division today, so that
 * {@code roundAbout/egglog/run_facts.py} can compare the two. Off unless
 * {@code -DexportEgglog=<dir>} or the environment variable {@code EXPORT_EGGLOG=<dir>} is set; it
 * only writes files and never changes the analysis.
 *
 * <p>Each division's operands are followed through {@code +}, {@code -}, {@code *} and {@code /};
 * any other value becomes {@code (Leaf vn)}. Two reads of the same struct member become one
 * {@code (Field ref name)} under the condition {@link RoundingRecognition}'s structural equality
 * uses, so the e-graph equates exactly the values today's matcher equates. The terms are written
 * as one {@code let} per SSA value, so a shared subterm is written once.
 */
final class EgglogExport {
	static final String DIR = System.getProperty("exportEgglog", System.getenv("EXPORT_EGGLOG"));

	/** Writes the facts for {@code n} if exporting is on; never throws. */
	static void export(CGNode n, RoundingRecognition recognition) {
		if (DIR == null || n.getIR() == null) {
			return;
		}
		try {
			new EgglogExport(n, recognition).write();
		} catch (IOException | RuntimeException e) {
			System.err.println("exportEgglog: " + n + ": " + e);
		}
	}

	private final CGNode node;
	private final IR ir;
	private final DefUse du;
	private final SymbolTable st;
	private final RoundingRecognition recognition;
	private final Map<Integer, String> names = HashMapFactory.make();
	private final StringBuilder lets = new StringBuilder();
	private final StringBuilder roots = new StringBuilder();

	private EgglogExport(CGNode node, RoundingRecognition recognition) {
		this.node = node;
		this.ir = node.getIR();
		this.du = node.getDU();
		this.st = ir.getSymbolTable();
		this.recognition = recognition;
	}

	private void write() throws IOException {
		int count = 0;
		for (SSAInstruction inst : ir.getInstructions()) {
			if (inst instanceof SSABinaryOpInstruction div
					&& div.getOperator() == IBinaryOpInstruction.Operator.DIV) {
				addRoot(div);
				count++;
			}
		}
		if (count == 0) {
			return;
		}
		StringBuilder out = new StringBuilder();
		out.append(";@method ").append(node.getMethod().getSignature()).append('\n');
		out.append(";@context ").append(oneLine(node.getContext().toString())).append('\n');
		out.append(";@source ").append(sourcePath()).append('\n');
		out.append(lets).append(roots);
		Path dir = Paths.get(DIR);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve(fileName()), out.toString(), StandardCharsets.UTF_8);
	}

	/** {@code (Root v t)}, a {@code ;@root} line saying where the division is, and today's decision. */
	private void addRoot(SSABinaryOpInstruction div) {
		int v = div.getDef();
		String t = term(v);
		roots.append(";@root ").append(v).append(' ').append(position(div)).append('\n');
		roots.append("(Root ").append(v).append(' ').append(t).append(")\n");
		Direction today = recognition.divDirection(div);
		RoundingRecognition.Ceiling c = recognition.ceiling(v);
		if (today == Direction.Up && c != null) {
			roots.append("(TodayUp ").append(v).append(' ').append(product(c.dividendFactors)).append(' ')
					.append(term(c.divisorVN)).append(")\n");
		} else if (today == Direction.Neither) {
			roots.append("(TodayExact ").append(v).append(")\n");
		}
	}

	/** The factors multiplied together, as an expression over their {@code let} names. */
	private String product(int[] factors) {
		String p = term(factors[factors.length - 1]);
		for (int i = factors.length - 2; i >= 0; i--) {
			p = "(Mul " + term(factors[i]) + " " + p + ")";
		}
		return p;
	}

	/** The {@code let} name of {@code vn}'s term, writing the term (operands first) the first time. */
	private String term(int vn) {
		String name = names.get(vn);
		if (name != null) {
			return name;
		}
		String body = body(vn);
		name = "$v" + vn;
		names.put(vn, name);
		lets.append("(let ").append(name).append(' ').append(body).append(")\n");
		return name;
	}

	private String body(int vn) {
		if (st.isConstant(vn)) {
			Object c = st.getConstantValue(vn);
			if (c instanceof Number num) {
				BigInteger b = num instanceof BigInteger bi ? bi : BigInteger.valueOf(num.longValue());
				return b.bitLength() < 64 ? "(Num " + b + ")" : "(BigNum \"" + b + "\")";
			}
			return "(Leaf " + vn + ")";
		}
		SSAInstruction def = du.getDef(vn);
		if (def instanceof SSABinaryOpInstruction bin) {
			IBinaryOpInstruction.IOperator o = bin.getOperator();
			String op = o == IBinaryOpInstruction.Operator.ADD ? "Add"
					: o == IBinaryOpInstruction.Operator.SUB ? "Sub"
					: o == IBinaryOpInstruction.Operator.MUL ? "Mul"
					: o == IBinaryOpInstruction.Operator.DIV ? "Div"
					: null;
			if (op != null) {
				String l = term(bin.getUse(0));
				String r = term(bin.getUse(1));
				return "(" + op + " " + l + " " + r + ")";
			}
		}
		if (def instanceof SSAGetInstruction g && !g.isStatic()
				&& PositionValues.isStructField(g.getDeclaredField())
				&& RoundingRecognition.unwrittenAfterInit(g.getRef(), g.getDeclaredField(), du)) {
			return "(Field " + g.getRef() + " \"" + g.getDeclaredField().getName() + "\")";
		}
		return "(Leaf " + vn + ")";
	}

	private String sourcePath() {
		if (ir.getMethod() instanceof AstMethod m && m.debugInfo().getCodeBodyPosition() != null) {
			return m.debugInfo().getCodeBodyPosition().getURL().getPath();
		}
		return "?";
	}

	private String position(SSAInstruction inst) {
		if (ir.getMethod() instanceof AstMethod m) {
			Position p = m.debugInfo().getInstructionPosition(inst.iIndex());
			if (p != null && p.getURL() != null) {
				return Paths.get(p.getURL().getPath()).getFileName() + ":" + p.getFirstLine();
			}
		}
		return "?";
	}

	/** Readable and stable: the method name plus a hash of source, signature and context. */
	private String fileName() {
		String readable = node.getMethod().getDeclaringClass().getName().getClassName() + "."
				+ node.getMethod().getName();
		readable = readable.replaceAll("[^A-Za-z0-9._-]", "_");
		if (readable.length() > 80) {
			readable = readable.substring(0, 80);
		}
		String key = sourcePath() + "\n" + node.getMethod().getSignature() + "\n" + node.getContext();
		return readable + "_" + sha1(key).substring(0, 10) + ".egg";
	}

	private static String sha1(String s) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : d) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String oneLine(String s) {
		return s.replace('\n', ' ').replace('\r', ' ');
	}
}

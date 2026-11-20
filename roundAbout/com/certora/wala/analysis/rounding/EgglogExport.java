/*
 * Copyright (c) 2024 Certora
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package com.certora.wala.analysis.rounding;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import com.ibm.wala.cast.loader.AstMethod;
import com.ibm.wala.cast.tree.CAstSourcePositionMap.Position;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.shrike.shrikeBT.IBinaryOpInstruction;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;

/**
 * E-graph prototype: writes the arithmetic around each candidate idiom of a call-graph node as
 * egglog facts, together with what phase 1 decides there today, so that
 * {@code roundAbout/egglog/run_facts.py} can compare the two. Off unless
 * {@code -DexportEgglog=<dir>} or the environment variable {@code EXPORT_EGGLOG=<dir>} is set; it
 * only writes files and never changes the analysis.
 *
 * <p>Terms follow {@code +}, {@code -}, {@code *}, {@code /} and {@code %}. An integer constant
 * becomes {@code (Num c)} with its exact value, whatever its size. A call the library
 * model treats as {@code floor(a_1 * ... * a_k / d)} becomes a {@code Div}. A 0/1 value that
 * phase 1's literal analysis recognizes (a comparison with zero, a {@code c ? 1 : 0} select, a
 * call such as {@code SafeCast.toUint}) becomes {@code (Ind base c)}, 1 exactly when
 * {@code base != 0}, or {@code (IndZero base c)}, 1 exactly when {@code base == 0}; {@code c} is 0,
 * or names the phi operands that must be infeasible for that to hold (the flag form), listed in a
 * {@code ;@cond} line. Two reads of the same unwritten struct member become one {@code (Field ref
 * name)}, as in {@link RoundingRecognition}'s structural equality. Any other value is
 * {@code (Leaf vn)}. Each SSA value is written once, as a {@code let}.
 *
 * <p>Roots: every division (the bias row), every addition and multiplication with an indicator
 * in its operands (the indicator and masked rows), and every value today's matcher reads as a
 * ceiling. Today's ceilings at phis (the branch row) are listed in {@code ;@uncovered} lines.
 *
 * <p>The same terms feed {@link EgglogPhase1}, which lets the rules decide the bias, indicator and
 * masked rows in place of the Java matchers. For that use the facts are built before any ceiling
 * is decided, so they hold no {@code TodayUp} rows, and they name every value for the rules with
 * a {@code (Val id term)} fact.
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
		} catch (IOException | RuntimeException | StackOverflowError e) {
			System.err.println("exportEgglog: " + n + ": " + e);
		}
	}

	/** Null when the facts are built for {@link EgglogPhase1}. */
	private final CGNode node;
	/** False for {@link EgglogPhase1}: the ceilings are not decided yet, so none is read or written. */
	private final boolean withToday;
	private final IR ir;
	private final DefUse du;
	private final SymbolTable st;
	private final RoundingRecognition recognition;
	private final IntFunction<RoundingRecognition.Literal> literals;
	private final Map<Integer, String> names = HashMapFactory.make();
	private final Map<Integer, Boolean> hasInd = HashMapFactory.make();
	private final Set<Integer> inProgress = HashSetFactory.make();
	private final Map<String, Integer> conditions = HashMapFactory.make();
	private final Map<Integer, List<int[]>> deadByCondition = HashMapFactory.make();
	/** The ids each value's term is built from, in the order the term names them. */
	private final Map<Integer, List<Integer>> operands = HashMapFactory.make();
	private final Deque<Integer> building = new ArrayDeque<>();
	/** -(call value) -> the factors a_1, ..., a_k of a call modelled as floor(a_1 * ... * a_k / d). */
	private final Map<Integer, int[]> products = HashMapFactory.make();
	private final Set<Integer> indicators = HashSetFactory.make();
	private final List<SSABinaryOpInstruction> rootInsts = new ArrayList<>();
	private final StringBuilder lets = new StringBuilder();
	private final StringBuilder roots = new StringBuilder();
	private final StringBuilder notes = new StringBuilder();

	private EgglogExport(CGNode node, RoundingRecognition recognition) {
		this.node = node;
		this.withToday = true;
		this.ir = node.getIR();
		this.du = node.getDU();
		this.st = ir.getSymbolTable();
		this.recognition = recognition;
		this.literals = recognition.literals();
	}

	/** For {@link EgglogPhase1}: {@code recognition} has decided no division and no ceiling yet. */
	EgglogExport(IR ir, DefUse du, RoundingRecognition recognition) {
		this.node = null;
		this.withToday = false;
		this.ir = ir;
		this.du = du;
		this.st = ir.getSymbolTable();
		this.recognition = recognition;
		this.literals = recognition.literals();
	}

	private int addRoots() {
		for (SSAInstruction inst : ir.getInstructions()) {
			if (inst instanceof SSABinaryOpInstruction bin && isRoot(bin)) {
				addRoot(bin);
			}
		}
		return rootInsts.size();
	}

	/**
	 * The facts for {@link EgglogPhase1}: the terms, a {@code Root} per candidate, and a
	 * {@code (Val id term)} per value; null if the method has no candidate.
	 */
	String phase1Facts() {
		if (addRoots() == 0) {
			return null;
		}
		StringBuilder vals = new StringBuilder();
		names.keySet().stream().sorted().forEach(vn -> vals.append("(Val ").append(vn).append(' ').append(names.get(vn)).append(")\n"));
		products.keySet().stream().sorted().forEach(id -> vals.append("(Val ").append(id).append(" $p").append(-id).append(")\n"));
		return notes.toString() + lets + roots + vals;
	}

	/** The candidates {@link #phase1Facts} wrote a {@code Root} for. */
	List<SSABinaryOpInstruction> rootInstructions() {
		return rootInsts;
	}

	/**
	 * The ids root {@code v}'s expression is built from, each once, in the order the expression
	 * reaches them; an addition or multiplication is read through its indicator operand last.
	 */
	List<Integer> walk(int v) {
		List<Integer> order = new ArrayList<>();
		Set<Integer> seen = HashSetFactory.make();
		Deque<Integer> stack = new ArrayDeque<>();
		stack.push(v);
		while (!stack.isEmpty()) {
			int id = stack.pop();
			if (!seen.add(id)) {
				continue;
			}
			order.add(id);
			List<Integer> ops = new ArrayList<>(operands.getOrDefault(id, List.of()));
			if (id == v) {
				ops.sort((a, b) -> Boolean.compare(indicators.contains(a), indicators.contains(b)));
			}
			for (int i = ops.size() - 1; i >= 0; i--) {
				stack.push(ops.get(i));
			}
		}
		return order;
	}

	/** The operands of {@code id}'s term, in the order the term names them. */
	List<Integer> operandsOf(int id) {
		return operands.getOrDefault(id, List.of());
	}

	/** The factors behind a negative id of {@link #walk}; null for a value number. */
	int[] productFactors(int id) {
		return products.get(id);
	}

	/** The phi operands, as {phi def, operand index}, that condition {@code c} needs infeasible. */
	List<int[]> dead(int c) {
		return c == 0 ? List.of() : deadByCondition.get(c);
	}

	private void write() throws IOException {
		int count = addRoots();
		for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
			for (Iterator<SSAPhiInstruction> phis = bb.iteratePhis(); phis.hasNext();) {
				SSAPhiInstruction phi = phis.next();
				RoundingRecognition.Ceiling c = recognition.ceiling(phi.getDef());
				if (c != null) {
					notes.append(";@uncovered ").append(phi.getDef()).append(' ').append(c.kind).append('\n');
				}
			}
		}
		if (count == 0) {
			return;
		}
		StringBuilder out = new StringBuilder();
		out.append(";@method ").append(node.getMethod().getSignature()).append('\n');
		out.append(";@context ").append(oneLine(node.getContext().toString())).append('\n');
		out.append(";@source ").append(sourcePath()).append('\n');
		out.append(notes).append(lets).append(roots);
		Path dir = Paths.get(DIR);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve(fileName()), out.toString(), StandardCharsets.UTF_8);
	}

	private boolean isRoot(SSABinaryOpInstruction bin) {
		IBinaryOpInstruction.IOperator o = bin.getOperator();
		if (o == IBinaryOpInstruction.Operator.DIV) {
			return true;
		}
		if (o == IBinaryOpInstruction.Operator.ADD || o == IBinaryOpInstruction.Operator.MUL) {
			return (withToday && recognition.ceiling(bin.getDef()) != null) || hasInd(bin.getUse(0)) || hasInd(bin.getUse(1));
		}
		return false;
	}

	/**
	 * {@code (Root v t)}, a {@code ;@root v kind File.sol:line first-last} line saying what and
	 * where it is, and today's decision.
	 */
	private void addRoot(SSABinaryOpInstruction bin) {
		int v = bin.getDef();
		String t = term(v);
		String kind = bin.getOperator().toString().toLowerCase();
		roots.append(";@root ").append(v).append(' ').append(kind).append(' ').append(position(bin)).append('\n');
		roots.append("(Root ").append(v).append(' ').append(t).append(")\n");
		rootInsts.add(bin);
		if (!withToday) {
			return;
		}
		RoundingRecognition.Ceiling c = recognition.ceiling(v);
		if (c != null) {
			roots.append(";@today ").append(v).append(' ').append(c.kind).append('\n');
			roots.append("(TodayUp ").append(v).append(' ').append(product(c.dividendFactors)).append(' ')
					.append(term(c.divisorVN)).append(' ').append(condition(c.deadPhiOperands)).append(")\n");
		} else if (bin.getOperator() == IBinaryOpInstruction.Operator.DIV
				&& recognition.divDirection(bin) == Direction.Neither) {
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

	/** 0 for no condition; otherwise a number for this set of phi operands, listed in a {@code ;@cond} line. */
	private int condition(List<int[]> dead) {
		if (dead.isEmpty()) {
			return 0;
		}
		List<String> parts = new ArrayList<>();
		for (int[] d : dead) {
			parts.add(d[0] + ":" + d[1]);
		}
		parts.sort(null);
		String key = String.join(",", parts);
		Integer id = conditions.get(key);
		if (id == null) {
			id = conditions.size() + 1;
			conditions.put(key, id);
			deadByCondition.put(id, List.copyOf(dead));
			notes.append(";@cond ").append(id).append(' ').append(key).append('\n');
		}
		return id;
	}

	/** The {@code let} name of {@code vn}'s term, writing the term (operands first) the first time. */
	private String term(int vn) {
		if (!building.isEmpty()) {
			operands.computeIfAbsent(building.peek(), k -> new ArrayList<>()).add(vn);
		}
		String name = names.get(vn);
		if (name != null) {
			return name;
		}
		building.push(vn);
		String body = inProgress.add(vn) ? body(vn) : "(Leaf " + vn + ")"; // a cycle through phis
		building.pop();
		inProgress.remove(vn);
		name = "$v" + vn;
		names.put(vn, name);
		lets.append("(let ").append(name).append(' ').append(body).append(")\n");
		return name;
	}

	private String body(int vn) {
		if (st.isConstant(vn)) {
			Object c = st.getConstantValue(vn);
			BigInteger b = c instanceof Number num ? integerValue(num) : null;
			if (b == null) {
				return "(Leaf " + vn + ")";
			}
			// egglog's (bigint n) takes a 64-bit literal; a larger constant is written as a decimal string
			return b.bitLength() < 64 ? "(Num (bigint " + b + "))" : "(Num (from-string \"" + b + "\"))";
		}
		SSAInstruction def = du.getDef(vn);
		if (def instanceof SSABinaryOpInstruction bin) {
			String op = arithmetic(bin.getOperator());
			if (op != null) {
				String l = term(bin.getUse(0));
				String r = term(bin.getUse(1));
				return "(" + op + " " + l + " " + r + ")";
			}
		}
		if (def instanceof SSAAbstractInvokeInstruction call && recognition.isFloorQuotient(call)
				&& call.getNumberOfUses() >= 3) {
			// uses are [callee, a_1, ..., a_k, d]
			int n = call.getNumberOfUses();
			String num = term(call.getUse(n - 2));
			for (int i = n - 3; i >= 1; i--) {
				num = "(Mul " + term(call.getUse(i)) + " " + num + ")";
			}
			if (n > 3) {
				// the product has no value number of its own: name it, as -(the call's value)
				int[] factors = new int[n - 2];
				for (int i = 1; i < n - 1; i++) {
					factors[i - 1] = call.getUse(i);
				}
				products.put(-vn, factors);
				operands.computeIfAbsent(vn, k -> new ArrayList<>()).add(-vn);
				lets.append("(let $p").append(vn).append(' ').append(num).append(")\n");
				num = "$p" + vn;
			}
			return "(Div " + num + " " + term(call.getUse(n - 1)) + ")";
		}
		RoundingRecognition.Literal lit = literals.apply(vn);
		if (lit.indicator && lit.base != vn) {
			String base = term(lit.base);
			indicators.add(vn);
			return "(" + (lit.positive ? "Ind" : "IndZero") + " " + base + " " + condition(lit.dead) + ")";
		}
		if (def instanceof SSAGetInstruction g && !g.isStatic()
				&& PositionValues.isStructField(g.getDeclaredField())
				&& RoundingRecognition.unwrittenAfterInit(g.getRef(), g.getDeclaredField(), du)) {
			return "(Field " + g.getRef() + " \"" + g.getDeclaredField().getName() + "\")";
		}
		return "(Leaf " + vn + ")";
	}

	/**
	 * The exact value of an integer constant, or null if the constant is not an integer. The
	 * frontend holds a literal in scientific notation, such as {@code 1e27}, as a
	 * {@link BigDecimal}, whose {@code longValue()} keeps only the low 64 bits.
	 */
	private static BigInteger integerValue(Number num) {
		if (num instanceof BigInteger bi) {
			return bi;
		}
		if (num instanceof BigDecimal bd) {
			try {
				return bd.toBigIntegerExact();
			} catch (ArithmeticException e) {
				return null;
			}
		}
		if (num instanceof Double || num instanceof Float) {
			double d = num.doubleValue();
			return d == Math.rint(d) && !Double.isInfinite(d) ? new BigDecimal(d).toBigIntegerExact() : null;
		}
		return BigInteger.valueOf(num.longValue());
	}

	private static String arithmetic(IBinaryOpInstruction.IOperator o) {
		return o == IBinaryOpInstruction.Operator.ADD ? "Add"
				: o == IBinaryOpInstruction.Operator.SUB ? "Sub"
				: o == IBinaryOpInstruction.Operator.MUL ? "Mul"
				: o == IBinaryOpInstruction.Operator.DIV ? "Div"
				: o == IBinaryOpInstruction.Operator.REM ? "Rem"
				: null;
	}

	/** True if {@code vn} is an indicator, or a sum or product with one among its operands. */
	private boolean hasInd(int vn) {
		Boolean known = hasInd.get(vn);
		if (known != null) {
			return known;
		}
		hasInd.put(vn, false); // a cycle through phis
		boolean result = false;
		if (!st.isConstant(vn)) {
			SSAInstruction def = du.getDef(vn);
			if (def instanceof SSABinaryOpInstruction bin && (bin.getOperator() == IBinaryOpInstruction.Operator.ADD
					|| bin.getOperator() == IBinaryOpInstruction.Operator.MUL)) {
				result = hasInd(bin.getUse(0)) || hasInd(bin.getUse(1));
			} else if (!(def instanceof SSABinaryOpInstruction bin2 && arithmetic(bin2.getOperator()) != null)) {
				RoundingRecognition.Literal lit = literals.apply(vn);
				result = lit.indicator && lit.base != vn;
			}
		}
		hasInd.put(vn, result);
		return result;
	}

	private String sourcePath() {
		if (ir.getMethod() instanceof AstMethod m && m.debugInfo().getCodeBodyPosition() != null) {
			return m.debugInfo().getCodeBodyPosition().getURL().getPath();
		}
		return "?";
	}

	/**
	 * {@code File.sol:line first-last}: where the instruction is, and the offsets of its source text.
	 * The offsets name the same source expression in every context the method is analyzed in; the
	 * value number does not, because each context has its own IR.
	 */
	private String position(SSAInstruction inst) {
		if (ir.getMethod() instanceof AstMethod m) {
			Position p = m.debugInfo().getInstructionPosition(inst.iIndex());
			if (p != null && p.getURL() != null) {
				return Paths.get(p.getURL().getPath()).getFileName() + ":" + p.getFirstLine() + " "
						+ p.getFirstOffset() + "-" + p.getLastOffset();
			}
		}
		return "? ?";
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

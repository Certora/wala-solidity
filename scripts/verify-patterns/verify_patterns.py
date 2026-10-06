#!/usr/bin/env python3
"""SMT verification of phase 1's rewrite patterns (behavior preservation).

Each pattern ell => r must satisfy [[ell]]_Z = [[r]]_Z under its side conditions;
[[r]]_Z is the integer meaning Q assigns (a Div node computes round(N/D) per rounds()).
Schemas are encoded over mathematical integers with the analysis's own assumptions as
constraints (non-negative values, divisor >= 1; the global no-overflow assumption).
Every division is introduced through an explicit quotient/remainder witness
(N = D*q + r, 0 <= r < D), which defines floor(N/D) = q and ceil(N/D) = q + [r != 0]
without symbolic-divisor `div`, keeping Z3 fast and predictable. We assert the
negation of each schema and expect unsat; shift identities are proved over 256-bit
bitvectors. A FALSIFIED row prints Z3's counterexample.

Expected outcome: every recognizer schema verifies; the bias-division rule as
implemented (classify(): any shared non-constant between the divisor and a dividend
addend => Up, with Q keeping the whole dividend as numerator) is falsified - the
soundness ledger's item 1 - while the b-1 side condition verifies as the fix target.

Run: python3 verify_patterns.py   (needs pip package z3-solver)
"""
import itertools
import time

from z3 import (And, BitVec, BitVecVal, If, Int, LShR, Not, Solver, UDiv, ULT, sat,
                unsat)

_fresh = itertools.count()


def floordiv(s, n, d):
    """floor(n/d) for d >= 1, via the witness pair; returns (q, r)."""
    i = next(_fresh)
    q, r = Int(f'q{i}'), Int(f'r{i}')
    s.add(n == d * q + r, r >= 0, r < d)
    return q, r


def ceil(s, n, d):
    q, r = floordiv(s, n, d)
    return q + If(r != 0, 1, 0)


CASES = []


def schema(name, expect_verified=True):
    def wrap(f):
        CASES.append((name, f, expect_verified))
        return f
    return wrap


@schema("branch/indicator ceiling: N/D + [N%D != 0]  =>  ceil(N/D)")
def _(s):
    n, d = Int('N'), Int('D')
    s.add(n >= 0, d >= 1)
    q, r = floordiv(s, n, d)
    s.add(Not(q + If(r != 0, 1, 0) == ceil(s, n, d)))


@schema("guarded predecrement: N != 0 |- (N-1)/D + 1  =>  ceil(N/D)")
def _(s):
    n, d = Int('N'), Int('D')
    s.add(n >= 1, d >= 1)
    q, _r = floordiv(s, n - 1, d)
    s.add(Not(q + 1 == ceil(s, n, d)))


@schema("masked predecrement: [N != 0] * ((N-1)/D + 1)  =>  ceil(N/D)  (incl. N = 0)")
def _(s):
    n, d = Int('N'), Int('D')
    s.add(n >= 0, d >= 1)
    q, _r = floordiv(s, n - 1, d)   # Euclidean at n = 0: q = -1, r = d - 1
    s.add(Not(If(n != 0, 1, 0) * (q + 1) == ceil(s, n, d)))


@schema("division by one: N/1  =>  N exactly, and N/1 + 0 likewise")
def _(s):
    n = Int('N')
    s.add(n >= 0)
    q, _r = floordiv(s, n, Int('one'))
    s.add(Int('one') == 1)
    s.add(Not(And(q == n, q + 0 == n)))


@schema("right shift is floor division by 2^k (BV256, k enumerated 0..255)")
def _(s):
    # UDiv by a symbolic power of two defeats bit-blasting; k is bounded, so the
    # universal claim is the finite conjunction over every k.
    x = BitVec('x', 256)
    s.add(Not(And([LShR(x, k) == UDiv(x, BitVecVal(1, 256) << k) for k in range(256)])))


@schema("left shift is multiplication by 2^k (BV256, both wrap alike)")
def _(s):
    x, k = BitVec('x', 256), BitVec('k', 256)
    s.add(ULT(k, BitVecVal(256, 256)))
    s.add(Not((x << k) == x * (BitVecVal(1, 256) << k)))


@schema("bias rule AS IMPLEMENTED: divisor shared with a dividend addend => Up "
        "(Q claims floor((a+b)/b) = ceil((a+b)/b))", expect_verified=False)
def _(s):
    a, b = Int('a'), Int('b')
    s.add(a >= 0, b >= 2)
    q, _r = floordiv(s, a + b, b)
    s.add(Not(q == ceil(s, a + b, b)))


@schema("bias rule with the b-1 side condition: (a+b-1)/b  =>  ceil(a/b)")
def _(s):
    a, b = Int('a'), Int('b')
    s.add(a >= 0, b >= 1)
    q, _r = floordiv(s, a + b - 1, b)
    s.add(Not(q == ceil(s, a, b)))


def main():
    import os
    import sys
    outdir = sys.argv[1] if len(sys.argv) > 1 else None
    log = None
    if outdir:
        os.makedirs(outdir, exist_ok=True)
        log = open(os.path.join(outdir, 'run.log'), 'w')

    def emit(line):
        print(line, flush=True)
        if log:
            log.write(line + '\n')

    import z3
    emit(f"z3 {z3.get_version_string()}  {time.strftime('%Y-%m-%d %H:%M:%S')}")
    width = max(len(n) for n, _, _ in CASES)
    failures = 0
    for i, (name, build, expect_verified) in enumerate(CASES):
        s = Solver()
        s.set("timeout", 120_000)
        build(s)
        if outdir:
            slug = f"{i:02d}-" + ''.join(c if c.isalnum() else '-' for c in name.split(':')[0])[:48]
            with open(os.path.join(outdir, slug + '.smt2'), 'w') as f:
                f.write(f"; {name}\n; negation asserted: unsat = schema verified\n")
                f.write(s.to_smt2())
        t0 = time.time()
        res = s.check()
        ms = int((time.time() - t0) * 1000)
        if res == unsat:
            status, ok = "VERIFIED", expect_verified
        elif res == sat:
            status, ok = f"FALSIFIED  model: {s.model()}", not expect_verified
        else:
            status, ok = str(res), False
        marker = "" if ok else "  << UNEXPECTED"
        failures += 0 if ok else 1
        emit(f"{name:<{width}}  [{ms:>6} ms]  {status}{marker}")
    emit("")
    emit("all outcomes as expected" if failures == 0 else f"{failures} UNEXPECTED OUTCOMES")
    if log:
        log.close()
    return failures


if __name__ == '__main__':
    raise SystemExit(main())

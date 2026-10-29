#!/usr/bin/env python3
"""SMT verification of phase 2's transfer functions (technical.tex, Table 1 and the clamp rule).

A direction d describes a pair (n, r): n is a value of the integer run, r the matching
value of the real run, and gamma(d) is the set of pairs it allows:
  Exact: n = r    Up: n >= r    Down: n <= r    Indet: any pair.

For an operator op and operand directions (d1, d2), soundness (Lemma 3.2) says:
  if (n1, r1) in gamma(d1), (n2, r2) in gamma(d2), and both runs evaluate op
  (subtraction needs a non-negative result, division a non-zero divisor),
  then (n, r) in gamma(transfer(op, d1, d2)).
We assert the hypotheses and the NEGATION of the conclusion and expect unsat.
Values are non-negative in both runs (states and constants are unsigned). Integer
values are Ints, real values Reals; integer division goes through a quotient/remainder
witness (n1 = n2*q + rem, 0 <= rem < n2), as in verify_patterns.py.

Besides soundness, each case reports whether a strictly more precise direction would
also be valid ("could be d'"): that is imprecision, not unsoundness.

The clamp rule (Section 3.6) is checked the same way: for
  if g <op> b then x := b + k else x := g,   op in {<=, <},
with g Down and b Exact, k <= 0 must give x Down, and for k >= 1 we show that neither
Down nor Up holds, which is why the rule says Indet.

Run: python3 verify_transfer_functions.py   (needs pip package z3-solver)
"""
import itertools
import sys
import time

from z3 import And, If, Int, Not, Real, Solver, ToReal, sat, unknown, unsat

EXACT, UP, DOWN, INDET = 'Exact', 'Up', 'Down', 'Indet'
DIRS = [EXACT, UP, DOWN, INDET]
# strictly more precise directions, for the precision report
BELOW = {EXACT: [], UP: [EXACT], DOWN: [EXACT], INDET: [EXACT, UP, DOWN]}


def flip(d):
    return {UP: DOWN, DOWN: UP}.get(d, d)


def combine(*ds):
    out = EXACT
    for d in ds:
        if out == INDET or d == INDET:
            out = INDET
        elif out == EXACT:
            out = d
        elif d != EXACT and d != out:
            out = INDET
    return out


def gamma(d, n, r):
    """The pair (n, r) is allowed by d. n is an Int, r a Real."""
    n = ToReal(n)
    return {EXACT: n == r, UP: n >= r, DOWN: n <= r, INDET: True}[d]


# --- the transfer functions of Table 1 ------------------------------------------------

def transfer(op, d1, d2):
    if op in ('+', '*'):
        return combine(d1, d2)
    if op == '-':
        return combine(d1, flip(d2))
    if op == 'divDown':
        return combine(DOWN, d1, flip(d2))
    if op == 'divUp':
        return combine(UP, d1, flip(d2))
    if op in ('<', '<=', '==', '!='):
        return EXACT if d1 == EXACT and d2 == EXACT else INDET
    raise ValueError(op)


# --- the semantics of Figure 3 ---------------------------------------------------------

_fresh = itertools.count()


def evaluate(s, op, n1, n2, r1, r2):
    """Adds the premises for both runs to evaluate op; returns (n, r)."""
    i = next(_fresh)
    if op == '+':
        return n1 + n2, r1 + r2
    if op == '*':
        return n1 * n2, r1 * r2
    if op == '-':
        s.add(n1 >= n2, r1 >= r2)                       # no derivation below zero
        return n1 - n2, r1 - r2
    if op in ('divDown', 'divUp'):
        s.add(n2 != 0, r2 != 0)                         # no derivation for a zero divisor
        q, rem = Int(f'q{i}'), Int(f'rem{i}')
        s.add(n1 == n2 * q + rem, rem >= 0, rem < n2)   # q = floor(n1 / n2)
        n = q if op == 'divDown' else q + If(rem != 0, 1, 0)
        r = Real(f'quot{i}')
        s.add(r * r2 == r1)                             # r = r1 / r2, exactly
        return n, r
    cmp = {'<': lambda a, b: a < b, '<=': lambda a, b: a <= b,
           '==': lambda a, b: a == b, '!=': lambda a, b: a != b}[op]
    return If(cmp(n1, n2), 1, 0), If(cmp(r1, r2), 1.0, 0.0)


def check(build):
    """build(s) adds hypotheses and a goal to refute; returns unsat/sat/unknown and a model."""
    s = Solver()
    s.set('timeout', 20000)
    build(s)
    res = s.check()
    return res, (s.model() if res == sat else None)


def operator_case(op, d1, d2, claim):
    """Is (n, r) in gamma(claim) for every valuation allowed by d1, d2?"""
    def build(s):
        n1, n2 = Int('n1'), Int('n2')
        r1, r2 = Real('r1'), Real('r2')
        s.add(n1 >= 0, n2 >= 0, r1 >= 0, r2 >= 0)
        s.add(gamma(d1, n1, r1), gamma(d2, n2, r2))
        n, r = evaluate(s, op, n1, n2, r1, r2)
        s.add(Not(gamma(claim, n, r)))
    return check(build)


def clamp_case(guard_op, k_cond, claim):
    def build(s):
        gz, b, k = Int('g_int'), Int('b'), Int('k')
        gr = Real('g_real')
        s.add(gz >= 0, b >= 0, gr >= 0, gamma(DOWN, gz, gr))   # g Down, b Exact (same in both runs)
        s.add(k_cond(k), b + k >= 0)                             # b + k must have a derivation
        test = (lambda a, c: a <= c) if guard_op == '<=' else (lambda a, c: a < c)
        xz = If(test(gz, b), b + k, gz)
        xr = If(test(gr, ToReal(b)), ToReal(b + k), gr)
        s.add(Not(gamma(claim, xz, xr)))
    return check(build)


def fmt_model(m):
    return ', '.join(f'{d.name()}={m[d]}' for d in sorted(m.decls(), key=lambda d: d.name())
                     if not d.name().startswith(('q', 'rem', 'quot')))


def main():
    t0 = time.time()
    ops = ['+', '*', '-', 'divDown', 'divUp', '<', '<=', '==', '!=']
    failures, unknowns, loose = [], [], []
    total = 0
    print('Transfer functions (Lemma 3.2): soundness of every operator x operand directions')
    for op in ops:
        row = []
        for d1, d2 in itertools.product(DIRS, DIRS):
            claim = transfer(op, d1, d2)
            total += 1
            res, model = operator_case(op, d1, d2, claim)
            if res == unsat:
                mark = 'ok'
                tighter = [d for d in BELOW[claim] if operator_case(op, d1, d2, d)[0] == unsat]
                if tighter:
                    loose.append((op, d1, d2, claim, tighter))
            elif res == sat:
                mark = 'FAIL'
                failures.append((op, d1, d2, claim, fmt_model(model)))
            else:
                mark = '?'
                unknowns.append((op, d1, d2, claim))
            row.append(mark)
        print(f'  {op:8s} {row.count("ok"):2d}/16 verified'
              + (f', {row.count("FAIL")} FALSIFIED' if 'FAIL' in row else '')
              + (f', {row.count("?")} unknown' if '?' in row else ''))

    print('\nClamp rule (Section 3.6): if g <op> b then x := b + k else x := g, g Down, b Exact')
    clamp_results = []
    for guard_op in ['<=', '<']:
        res, model = clamp_case(guard_op, lambda k: k <= 0, DOWN)
        clamp_results.append((guard_op, 'k <= 0', 'Down holds', res == unsat, model))
        for claim in [DOWN, UP]:
            res, model = clamp_case(guard_op, lambda k: k >= 1, claim)
            # for k >= 1 we EXPECT a counterexample: the rule says Indet because neither holds
            clamp_results.append((guard_op, 'k >= 1', f'{claim} fails (so Indet)', res == sat, model))
    for guard_op, kc, what, ok, model in clamp_results:
        extra = f'   e.g. {fmt_model(model)}' if model is not None and 'fails' in what else ''
        print(f'  g {guard_op:2s} b, {kc}: {what:22s} {"ok" if ok else "UNEXPECTED"}{extra}')

    print(f'\n{total} operator cases: {total - len(failures) - len(unknowns)} verified, '
          f'{len(failures)} falsified, {len(unknowns)} unknown')
    for op, d1, d2, claim, m in failures:
        print(f'  FALSIFIED {op}({d1}, {d2}) claimed {claim}: {m}')
    for op, d1, d2, claim in unknowns:
        print(f'  UNKNOWN   {op}({d1}, {d2}) claimed {claim}')
    if loose:
        print(f'\nImprecise (sound, but a more precise direction also holds): {len(loose)} cases')
        for op, d1, d2, claim, tighter in loose:
            print(f'  {op}({d1}, {d2}) claimed {claim}, could be {"/".join(tighter)}')
    print(f'\n{time.time() - t0:.1f}s')
    bad = failures or unknowns or not all(ok for *_, ok, _ in clamp_results)
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()

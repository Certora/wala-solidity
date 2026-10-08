#!/usr/bin/env python3
"""The paper's corpus table and headline percentages, from a returns extract.

Usage: python3 corpus_table.py <returns-extract>
Counts deduplicated (test | method | context | return) rows for the 21 corpus
configurations. A result is one returned value's direction: a function returning
a tuple contributes one result per component, counted in the four direction
columns like any other result.
"""
import collections
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from corpus import PROT

DIRECTIONS = ("Neither", "Down", "Up", "Inconsistent")


def compute(returns_path):
    """Returns dict(rows={test: Counter}, totals=Counter, results, tuples, pct={...})."""
    rows = sorted(set(open(returns_path).read().splitlines()))
    tab = collections.defaultdict(collections.Counter)
    tuples = 0
    for r in rows:
        t = r.split("|")[0].strip().rsplit("_", 1)[0]
        if t not in PROT:
            continue
        ret = r.split("return=", 1)[1]
        if ret.startswith("{"):
            tuples += 1
            for d in re.findall(r">=(\w+)", ret):
                tab[t][d] += 1
        else:
            tab[t][ret] += 1
    missing = sorted(PROT - set(tab))
    if missing:
        raise SystemExit(f"no rows for corpus tests: {missing}")
    unknown = {k for c in tab.values() for k in c} - set(DIRECTIONS)
    if unknown:
        raise SystemExit(f"unexpected verdicts in the extract: {sorted(unknown)}")
    tot = collections.Counter()
    for c in tab.values():
        tot.update(c)
    N = sum(tot.values())
    ex, dn, up, ind = (tot[k] for k in DIRECTIONS)
    return dict(rows=dict(tab), totals=tot, results=N, tuples=tuples,
                exact=ex, down=dn, up=up, indet=ind,
                pct=dict(exact=100 * ex / N, down=100 * dn / N, up=100 * up / N,
                         indet=100 * ind / N, definite=100 * (ex + dn + up) / N),
                non_exact=N - ex)


def main():
    r = compute(sys.argv[1])
    print(f"{'configuration':36s} {'n':>5} {'Exact':>6} {'Down':>5} {'Up':>4} {'Indet':>6}")
    for t, c in sorted(r['rows'].items(), key=lambda kv: -sum(kv[1].values())):
        print(f"{t:36s} {sum(c.values()):5d} {c['Neither']:6d} {c['Down']:5d} {c['Up']:4d} "
              f"{c['Inconsistent']:6d}")
    tot = r['totals']
    print(f"{'TOTAL':36s} {r['results']:5d} {tot['Neither']:6d} {tot['Down']:5d} {tot['Up']:4d} "
          f"{tot['Inconsistent']:6d}")
    p = r['pct']
    print(f"\n{r['results']} results ({r['tuples']} tuple-returning results counted per component):")
    print(f"  Exact {p['exact']:.1f}%  Down {p['down']:.1f}%  Up {p['up']:.1f}%  "
          f"Indet {p['indet']:.1f}%  definite {p['definite']:.1f}%  non-Exact {r['non_exact']}")


if __name__ == '__main__':
    main()

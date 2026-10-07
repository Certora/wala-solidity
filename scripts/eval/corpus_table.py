#!/usr/bin/env python3
"""The paper's corpus table and headline percentages, from a returns extract.

Usage: python3 corpus_table.py <returns-extract>
Counts deduplicated (test | method | context | return) rows for the 21 corpus
configurations; tuple components are tallied separately, as in the paper.
"""
import collections
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from corpus import PROT


def compute(returns_path):
    """Returns dict(rows={test: Counter}, totals=Counter, components=Counter, N, pct={...})."""
    rows = sorted(set(open(returns_path).read().splitlines()))
    tab = collections.defaultdict(collections.Counter)
    comp = collections.Counter()
    for r in rows:
        t = r.split("|")[0].strip().rsplit("_", 1)[0]
        if t not in PROT:
            continue
        ret = r.split("return=", 1)[1]
        if ret.startswith("{"):
            tab[t]["Tuple"] += 1
            for d in re.findall(r">=(\w+)", ret):
                comp[d] += 1
        else:
            tab[t][ret] += 1
    missing = sorted(PROT - set(tab))
    if missing:
        raise SystemExit(f"no rows for corpus tests: {missing}")
    tot = collections.Counter()
    for c in tab.values():
        for k in ("Neither", "Down", "Up", "Inconsistent", "Tuple"):
            tot[k] += c[k]
    n = sum(tot.values())
    N = n - tot["Tuple"] + sum(comp.values())
    ex, dn = tot["Neither"] + comp["Neither"], tot["Down"] + comp["Down"]
    up, ind = tot["Up"] + comp["Up"], tot["Inconsistent"] + comp["Inconsistent"]
    return dict(rows=dict(tab), totals=tot, results=n, components=comp, N=N,
                exact=ex, down=dn, up=up, indet=ind,
                pct=dict(exact=100 * ex / N, down=100 * dn / N, up=100 * up / N,
                         indet=100 * ind / N, definite=100 * (ex + dn + up) / N),
                non_exact=N - ex)


def main():
    r = compute(sys.argv[1])
    print(f"{'configuration':36s} {'n':>5} {'Exact':>6} {'Down':>5} {'Up':>4} {'Indet':>6} {'Tuple':>6}")
    for t, c in sorted(r['rows'].items(), key=lambda kv: -sum(kv[1].values())):
        n = sum(c.values())
        print(f"{t:36s} {n:5d} {c['Neither']:6d} {c['Down']:5d} {c['Up']:4d} "
              f"{c['Inconsistent']:6d} {c['Tuple']:6d}")
    tot = r['totals']
    print(f"{'TOTAL':36s} {r['results']:5d} {tot['Neither']:6d} {tot['Down']:5d} {tot['Up']:4d} "
          f"{tot['Inconsistent']:6d} {tot['Tuple']:6d}")
    p = r['pct']
    print(f"\nincl. tuple components, N={r['N']}:")
    print(f"  Exact {p['exact']:.1f}%  Down {p['down']:.1f}%  Up {p['up']:.1f}%  "
          f"Indet {p['indet']:.1f}%  definite {p['definite']:.1f}%  non-Exact {r['non_exact']}")


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Split the corpus Indet results by where they arise (eval 'Where the Indet results
come from'). Run from eval-artifacts/.

Usage: python3 indet_split.py <returns-extract>
A result is counted exactly as in corpus_table.py (deduplicated rows, one result per
tuple component). An Indet result is *inherited* when its calling context has an
Indet argument, so the function only passes on an upstream Indet; otherwise it
*originates* in the function, either from all-Exact arguments or where Up and Down
arguments meet.
"""
import collections
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from corpus import PROT


def compute(returns_path):
    """Returns dict(indet, inherited, origin, origin_exact, origin_mixed, rows={test: Counter})."""
    tab = collections.defaultdict(collections.Counter)
    for r in sorted(set(open(returns_path).read().splitlines())):
        p = [x.strip() for x in r.split("|")]
        t = p[0].rsplit("_", 1)[0]
        if t not in PROT:
            continue
        ret = r.split("return=", 1)[1]
        dirs = re.findall(r">=(\w+)", ret) if ret.startswith("{") else [ret]
        n = dirs.count("Inconsistent")
        if not n:
            continue
        ctx = p[2]
        if "Inconsistent" in ctx:
            tab[t]["inherited"] += n
        elif "Up" in ctx or "Down" in ctx:
            tab[t]["origin_mixed"] += n
        else:
            tab[t]["origin_exact"] += n
    tot = collections.Counter()
    for c in tab.values():
        tot.update(c)
    return dict(indet=sum(tot.values()), inherited=tot["inherited"],
                origin=tot["origin_exact"] + tot["origin_mixed"],
                origin_exact=tot["origin_exact"], origin_mixed=tot["origin_mixed"],
                rows=dict(tab))


def main():
    r = compute(sys.argv[1])
    print(f"{'configuration':36s} {'Indet':>6} {'inherit':>8} {'exact':>6} {'mixed':>6}")
    for t, c in sorted(r['rows'].items(), key=lambda kv: -sum(kv[1].values())):
        print(f"{t:36s} {sum(c.values()):6d} {c['inherited']:8d} {c['origin_exact']:6d} "
              f"{c['origin_mixed']:6d}")
    print(f"\n{r['indet']} Indet: {r['inherited']} inherited "
          f"({100 * r['inherited'] / r['indet']:.1f}%), {r['origin']} originate "
          f"({r['origin_exact']} from all-Exact arguments, {r['origin_mixed']} where Up and Down meet)")


if __name__ == '__main__':
    main()

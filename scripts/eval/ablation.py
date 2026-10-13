#!/usr/bin/env python3
"""Compare a full run with an ablated run of the same frozen jar (one flag off).

Usage: ablation.py <full-prefix> <ablated-prefix>
where each prefix names extracts written by extract.py (<prefix>-returns.txt,
<prefix>-roots.txt). Corpus configurations only.

Two views:
  headline     each run's corpus-table distribution (corpus_table.compute). An ablation
               changes the arguments callers pass, so the set of (function, context)
               results itself changes; the headlines are compared as distributions.
  entry points public functions analyzed in their root context, which no ablation
               changes, so each one is compared verdict to verdict. A tuple counts one
               verdict per component. Entry points whose (test, method, context) key
               occurs more than once are compared as multisets and only counted, not
               given a transition. A function analyzed in several copies that share one
               direction context but differ in a constant the caller passes (a rounding
               mode) can return different verdicts per copy; which copy is the graph's root
               is incidental, so such keys (more than one verdict for the key among all
               nodes, in either run) are left out and counted separately.
"""
import collections
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import corpus_table  # noqa: E402
from corpus import PROT  # noqa: E402


def _components(ret):
    if ret.startswith('{'):
        return re.findall(r'< solidity, L[^,]+, ([^,]+), <[^>]*> >=(\w+)', ret)
    return [('', ret)]


def _roots(path):
    rows = collections.defaultdict(list)
    for line in open(path):
        t, m, ctx, ret = [x.strip() for x in line.split(' | ', 3)]
        if t.rsplit('_', 1)[0] in PROT:
            rows[(t, m, ctx)].append(ret.removeprefix('return='))
    return rows


def _specialized(path):
    """(test, method, context) keys with more than one distinct return among all nodes."""
    rets = collections.defaultdict(set)
    for line in open(path):
        t, m, ctx, ret = [x.strip() for x in line.split(' | ', 3)]
        rets[(t, m, ctx)].add(ret)
    return {k for k, v in rets.items() if len(v) > 1}


def compute(full, abl):
    hf = corpus_table.compute(f'{full}-returns.txt')
    ha = corpus_table.compute(f'{abl}-returns.txt')
    rf, ra = _roots(f'{full}-roots.txt'), _roots(f'{abl}-roots.txt')
    if set(rf) != set(ra):
        raise SystemExit(f'entry points differ: {len(set(rf) ^ set(ra))} keys')
    spec = _specialized(f'{full}-returns.txt') | _specialized(f'{abl}-returns.txt')
    trans = collections.Counter()
    verdicts = changed = ambiguous_changed = specialized = 0
    functions = set()
    for k in rf:
        a, b = rf[k], ra[k]
        if k in spec:
            specialized += 1
            continue
        if len(a) == 1 and len(b) == 1:
            ca, cb = dict(_components(a[0])), dict(_components(b[0]))
            if set(ca) != set(cb):
                raise SystemExit(f'tuple shape differs at {k}')
            for i in ca:
                verdicts += 1
                if ca[i] != cb[i]:
                    changed += 1
                    trans[(ca[i], cb[i])] += 1
                    functions.add(k)
        else:
            n = sum(len(_components(r)) for r in a)
            verdicts += n
            if collections.Counter(a) != collections.Counter(b):
                ambiguous_changed += 1
                functions.add(k)
    return dict(full=hf, abl=ha, verdicts=verdicts, changed=changed, transitions=trans,
                ambiguous_changed=ambiguous_changed, functions=len(functions),
                specialized=specialized)


def main():
    r = compute(sys.argv[1], sys.argv[2])
    for name in ('full', 'abl'):
        h = r[name]; p = h['pct']
        print(f"{name:5s} {h['results']} results: Exact {p['exact']:.2f}%  Down {p['down']:.2f}%  "
              f"Up {p['up']:.2f}%  Indet {p['indet']:.2f}%")
    print(f"entry points: {r['changed']} of {r['verdicts']} verdicts change "
          f"({r['functions']} entry points; {r['ambiguous_changed']} repeated keys differ as multisets; "
          f"{r['specialized']} constant-specialized keys left out)")
    for (a, b), n in r['transitions'].most_common():
        print(f"  {a:>12} -> {b:<12} {n}")


if __name__ == '__main__':
    main()

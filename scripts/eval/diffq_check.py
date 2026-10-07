#!/usr/bin/env python3
"""Join the differential harness's TSVs (diffq/*.tsv) with the root verdict extract and
check every verdict against the observed integer-vs-rational relation.

Verdict semantics checked per sampled entry point:
  Up      -> no sample may fall below the rational run
  Down    -> no sample may fall above
  Neither -> every sample equal
  Inconsistent -> nothing required; both-sided observations CONFIRM the refusal.

Counts are reported at two granularities - entry-point ROWS (one per call-graph
entry node; a function can have several) and distinct FUNCTIONS (test, name) -
because the two have been confused before. Prose must name which one it cites.

Run from eval-artifacts/:  python3 ../scripts/eval/diffq_check.py [roots] [diffq-dir]
"""
import collections
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from corpus import CORPUS, tag as tag_of

TAGMAP = {tag_of(t): t for t, _, _, _ in CORPUS}


LABEL = re.compile(r'^graph of < solidity, (.*?), do\([^)]*\)[^>]*> \((.*)\)$')


def _verdicts(json_dir):
    """Root verdicts keyed by (test, full signature), all-exact contexts, scalar returns.

    The join must use the full signature: function NAMES are ambiguous (20 corpus
    names carry conflicting definite verdicts across contracts and overloads), while
    signatures carry no conflicts even across receivers - checked when this was written.
    """
    v = collections.defaultdict(set)
    for f in sorted(os.listdir(json_dir)):
        if not f.endswith('.json'):
            continue
        test = f[:-5].rsplit('_', 1)[0]
        for g in json.load(open(os.path.join(json_dir, f))).get('graphs', []):
            md = (g.get('nodes', {}).get('0') or {}).get('metadata') or {}
            m = LABEL.match(g.get('label', ''))
            if md.get('method') is None or not m:
                continue
            if not all(p.get('rounding') == 'Neither' for p in md.get('parameters', [])):
                continue
            ret = str(md.get('return'))
            if ret.startswith('{'):
                continue
            v[(test, m.group(1))].add(ret)
    conflicts = [k for k, s in v.items() if len(s - {'Inconsistent'}) > 1]
    if conflicts:
        raise SystemExit(f"signatures with conflicting definite verdicts: {conflicts[:5]}")
    return v


def compute(roots, d):
    seen_tags = {f[:-4] for f in os.listdir(d) if f.endswith('.tsv')}
    missing, extra = sorted(set(TAGMAP) - seen_tags), sorted(seen_tags - set(TAGMAP))
    if missing or extra:
        for t in missing:
            print(f"MISSING RESULTS for {TAGMAP[t]} ({t}.tsv)")
        for t in extra:
            print(f"UNEXPECTED RESULTS FILE {t}.tsv")
        raise SystemExit(1)
    verdicts = _verdicts(roots)
    c = collections.Counter()
    fns = collections.defaultdict(set)
    viols, confirmed = [], []
    samples = set()
    for f in sorted(os.listdir(d)):
        if not f.endswith('.tsv'):
            continue
        test = TAGMAP[f[:-4]]
        for line in open(os.path.join(d, f)).read().splitlines()[1:]:
            cols = line.split('\t')
            if len(cols) < 9:
                continue
            c['rows'] += 1
            samples.add(int(cols[2]))
            above, below, equal, amb, disc = map(int, cols[3:8])
            if above + below + equal == 0:
                c['void' if 'returns0' in cols[8] else 'out_of_scope'] += 1
                continue
            c['evaluated'] += 1
            key = (test, cols[0].split('.<Code body of function')[0])
            vs = verdicts.get(key)
            if not vs:
                c['no_verdict'] += 1
                continue

            def compatible(v):
                if v == 'Up':
                    return below == 0
                if v == 'Down':
                    return above == 0
                if v == 'Neither':
                    return above == 0 and below == 0
                return True

            if any(compatible(v) for v in vs):
                c['ok'] += 1
                fns['checked'].add(key)
                if vs == {'Inconsistent'}:
                    side = 'indet2' if above > 0 and below > 0 else 'indet1'
                    c[side] += 1
                    fns[side].add(key)
                    if side == 'indet2':
                        confirmed.append((test, key[1][:80], above, below, equal))
            else:
                c['viol'] += 1
                fns['checked'].add(key)
                viols.append((test, key[1], sorted(vs), above, below, equal, amb, disc))
    return dict(configs=len(seen_tags), counts=c,
                functions={k: len(v) for k, v in fns.items()},
                function_sets=dict(fns), violations=viols, confirmed=confirmed,
                samples_per_function=sorted(samples))


def main():
    roots = sys.argv[1] if len(sys.argv) > 1 else 'private-scratch'
    d = sys.argv[2] if len(sys.argv) > 2 else 'diffq'
    r = compute(roots, d)
    c, fn = r['counts'], r['functions']
    print(f"coverage: {r['configs']}/{len(TAGMAP)} corpus configurations")
    print(f"functions checked: {c['ok'] + c['viol']}  OK: {c['ok']}  VIOLATIONS: {c['viol']}")
    print(f"  Indet: two-sided confirmed {fn.get('indet2', 0)} functions ({c['indet2']} rows), "
          f"one-sided {fn.get('indet1', 0)} functions ({c['indet1']} rows)")
    print(f"  rows with no verdict match: {c['no_verdict']}, "
          f"fully discarded: {c['void'] + c['out_of_scope']}")
    print(f"  distinct functions checked: {fn.get('checked', 0)}; rows: total {c['rows']}, "
          f"void {c['void']}, evaluated {c['evaluated']}, out of scope {c['out_of_scope']}; "
          f"samples per function {r['samples_per_function']}")
    if r['violations']:
        print("\nVIOLATIONS (test, function, verdicts, above, below, equal, ambiguous, discarded):")
        for v in r['violations']:
            print("  ", v)
    if r['confirmed']:
        print("\nIndet confirmed two-sided (first 15):")
        for v in r['confirmed'][:15]:
            print("  ", v)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Join the differential harness's TSVs (diffq/*.tsv) with the root verdict extract and
check every verdict against the observed integer-vs-rational relation.

Verdict semantics checked per sampled function:
  Up      -> no sample may fall below the rational run
  Down    -> no sample may fall above
  Neither -> every sample equal
  Inconsistent -> nothing required; both-sided observations CONFIRM the refusal.

Run from eval-artifacts/:  python3 scripts/diffq_check.py [roots-extract] [diffq-dir]
"""
import collections
import os
import re
import sys

ROOTS = sys.argv[1] if len(sys.argv) > 1 else 'private-scratch-roots.txt'
DIR = sys.argv[2] if len(sys.argv) > 2 else 'diffq'

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from corpus import CORPUS, tag as tag_of

TAGMAP = {tag_of(t): t for t, _, _, _ in CORPUS}


def test_of(t):
    if t not in TAGMAP:
        raise SystemExit(f"results file with unmapped tag: {t} - update corpus.py")
    return TAGMAP[t]

# verdicts per (test, function-name): multiset of scalar returns in all-exact contexts
verdicts = collections.defaultdict(set)
for r in set(open(ROOTS).read().splitlines()):
    parts = [p.strip() for p in r.split('|')]
    if len(parts) < 4 or 'return=' not in r:
        continue
    test = parts[0].rsplit('_', 1)[0]
    m = re.search(r'<Code body of function ([^>]+)>', parts[1])
    if not m:
        continue
    ret = r.split('return=', 1)[1]
    if ret.startswith('{'):
        continue
    verdicts[(test, m.group(1))].add(ret)

ok = viol = nov = indet2 = indet1 = skipped = 0
indet1_fns, indet2_fns = set(), set()
viols = []
indet_confirmed = []
for f in sorted(os.listdir(DIR)):
    if not f.endswith('.tsv'):
        continue
    tag = f[:-4]
    test = test_of(tag)
    for line in open(os.path.join(DIR, f)).read().splitlines()[1:]:
        cols = line.split('\t')
        if len(cols) < 9:
            continue
        name_m = re.search(r'<Code body of function ([^>]+)>', cols[0])
        if not name_m or test is None:
            continue
        fn = name_m.group(1)
        above, below, equal, amb, disc = map(int, cols[3:8])
        evaluated = above + below + equal
        if evaluated == 0:
            skipped += 1
            continue
        vs = verdicts.get((test, fn))
        if not vs:
            nov += 1
            continue

        def compatible(v):
            if v == 'Up':
                return below == 0
            if v == 'Down':
                return above == 0
            if v == 'Neither':
                return above == 0 and below == 0
            return True  # Inconsistent: no claim

        if any(compatible(v) for v in vs):
            ok += 1
            if vs == {'Inconsistent'}:
                if above > 0 and below > 0:
                    indet2 += 1
                    indet2_fns.add((test, fn))
                    indet_confirmed.append((test, fn, above, below, equal))
                else:
                    indet1 += 1
                    indet1_fns.add((test, fn))
        else:
            viol += 1
            viols.append((test, fn, sorted(vs), above, below, equal, amb, disc))

seen_tags = {f[:-4] for f in os.listdir(DIR) if f.endswith('.tsv')}
expected = set(TAGMAP)
missing = sorted(expected - seen_tags)
extra = sorted(seen_tags - expected)
if missing or extra:
    for t in missing:
        print(f"MISSING RESULTS for {TAGMAP[t]} ({t}.tsv)")
    for t in extra:
        print(f"UNEXPECTED RESULTS FILE {t}.tsv")
    raise SystemExit(1)
print(f"coverage: {len(seen_tags)}/{len(expected)} corpus configurations")
print(f"functions checked: {ok + viol}  OK: {ok}  VIOLATIONS: {viol}")
print(f"  Indet: two-sided confirmed {len(indet2_fns)} functions ({indet2} rows), "
      f"one-sided {len(indet1_fns)} functions ({indet1} rows)")
print(f"  rows with no verdict match: {nov}, fully discarded: {skipped}")
if viols:
    print("\nVIOLATIONS (test, function, verdicts, above, below, equal, ambiguous, discarded):")
    for v in viols:
        print("  ", v)
if indet_confirmed:
    print("\nIndet confirmed two-sided (first 15):")
    for v in indet_confirmed[:15]:
        print("  ", v)

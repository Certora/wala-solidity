#!/usr/bin/env python3
"""Score the LLM transcripts against the manifest's ground truth.

Parse rule, fixed before any run: the verdict is the first line of the transcript
that consists of exactly one of the four words (case-insensitive, punctuation
stripped). A transcript with no such line scores as 'unparsed' - it is counted
and listed, never interpreted.

Outcome of one answer against the truth, also fixed in advance:
  correct          the answer equals the truth
  refusal          the answer is Indeterminate and the truth is not
  wrong-direction  a definite answer (Exact/Down/Up) that is not the truth
  unparsed         no verdict line
The tool's verdicts (from the manifest) are scored by the same rule.

Usage: score.py <manifest.json> <transcripts-dir> <out-csv>
"""
import collections
import csv
import json
import os
import re
import sys

WORDS = {'exact': 'Exact', 'down': 'Down', 'up': 'Up',
         'indeterminate': 'Indeterminate', 'indet': 'Indeterminate'}
TOOL = {'Neither': 'Exact', 'Down': 'Down', 'Up': 'Up', 'Inconsistent': 'Indeterminate'}
GROUPS = ('name', 'review', 'witness', 'exploit')
OUTCOMES = ('correct', 'refusal', 'wrong-direction', 'unparsed')


def parse(path):
    for line in open(path).read().splitlines():
        w = re.sub(r'[^a-z]', '', line.strip().lower())
        if w in WORDS:
            return WORDS[w]
    return 'unparsed'


def outcome(answer, truth):
    if answer == 'unparsed':
        return 'unparsed'
    if answer == truth:
        return 'correct'
    if answer == 'Indeterminate':
        return 'refusal'
    return 'wrong-direction'


def compute(manifest, tdir):
    """Returns dict(rows=[per-answer dicts], by_outcome={(system, cond): Counter},
    by_group={(system, cond): {group: correct-count}}, group_sizes={group: n})."""
    items = {i['id']: i for i in json.load(open(manifest))}
    rows = []
    for it in items.values():
        tool = TOOL.get(it['roundabout'], it['roundabout'])
        rows.append(dict(system='tool', cond='-', group=it['group'], id=it['id'],
                         truth=it['label'], answer=tool))
    for model in sorted(os.listdir(tdir)):
        for cond in ('named', 'anon'):
            d = os.path.join(tdir, model, cond)
            if not os.path.isdir(d):
                continue
            for f in sorted(os.listdir(d)):
                if not f.endswith('.txt'):
                    continue
                it = items.get(f[:-4])
                if it:
                    rows.append(dict(system=model, cond=cond, group=it['group'], id=it['id'],
                                     truth=it['label'], answer=parse(os.path.join(d, f))))
    for r in rows:
        r['outcome'] = outcome(r['answer'], r['truth'])
    by_outcome = collections.defaultdict(collections.Counter)
    by_group = collections.defaultdict(collections.Counter)
    for r in rows:
        key = (r['system'], r['cond'])
        by_outcome[key][r['outcome']] += 1
        if r['outcome'] == 'correct':
            by_group[key][r['group']] += 1
    sizes = collections.Counter(i['group'] for i in items.values())
    # every system must have answered every item, or the comparison is not like for like
    for key, c in by_outcome.items():
        if sum(c.values()) != len(items):
            raise SystemExit(f"{key} has {sum(c.values())} answers for {len(items)} items")
    return dict(rows=rows, by_outcome=dict(by_outcome), by_group=dict(by_group),
                group_sizes=dict(sizes), items=len(items))


def main():
    manifest, tdir, outcsv = sys.argv[1], sys.argv[2], sys.argv[3]
    r = compute(manifest, tdir)
    with open(outcsv, 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=list(r['rows'][0].keys()))
        w.writeheader()
        w.writerows(r['rows'])
    print(f"{'system':18s} {'cond':6s} " + " ".join(f"{o:>15s}" for o in OUTCOMES))
    for key in sorted(r['by_outcome']):
        print(f"{key[0]:18s} {key[1]:6s} " +
              " ".join(f"{r['by_outcome'][key][o]:15d}" for o in OUTCOMES))
    print(f"\ncorrect by group (sizes {r['group_sizes']}):")
    for key in sorted(r['by_group']):
        print(f"{key[0]:18s} {key[1]:6s} " +
              " ".join(f"{g}={r['by_group'][key][g]}" for g in GROUPS))
    print(f"\nper-answer rows written to {outcsv}")


if __name__ == '__main__':
    main()

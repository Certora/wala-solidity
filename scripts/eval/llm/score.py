#!/usr/bin/env python3
"""Score the LLM transcripts against the manifest's ground truth.

Parse rule, fixed before any run: the verdict is the first line of the transcript
that consists of exactly one of the four words (case-insensitive, punctuation
stripped). A transcript with no such line scores as 'unparsed' - it is counted
and listed, never interpreted.

Output: one row per (model, condition, group): n, model-vs-truth, tool-vs-truth,
model-vs-tool agreement; plus per-item CSV for the artifact.

Usage: score.py <manifest.json> <transcripts-dir> <out-csv>
"""
import csv
import json
import os
import re
import sys

WORDS = {'exact': 'Exact', 'down': 'Down', 'up': 'Up',
         'indeterminate': 'Indeterminate', 'indet': 'Indeterminate'}
TOOL = {'Neither': 'Exact', 'Down': 'Down', 'Up': 'Up', 'Inconsistent': 'Indeterminate'}


def parse(path):
    for line in open(path).read().splitlines():
        w = re.sub(r'[^a-z]', '', line.strip().lower())
        if w in WORDS:
            return WORDS[w]
        if line.strip():
            # first non-empty line is not a bare verdict; keep scanning a few more
            continue
    return 'unparsed'


def main():
    manifest, tdir, outcsv = sys.argv[1], sys.argv[2], sys.argv[3]
    items = {i['id']: i for i in json.load(open(manifest))}
    rows = []
    for model in sorted(os.listdir(tdir)):
        for cond in ('named', 'anon'):
            d = os.path.join(tdir, model, cond)
            if not os.path.isdir(d):
                continue
            for f in sorted(os.listdir(d)):
                if not f.endswith('.txt') or f.endswith('.err.txt'):
                    continue
                iid = f[:-4]
                it = items.get(iid)
                if not it:
                    continue
                ans = parse(os.path.join(d, f))
                rows.append(dict(model=model, cond=cond, group=it['group'], id=iid,
                                 truth=it['label'], tool=TOOL.get(it['roundabout'], it['roundabout']),
                                 llm=ans))
    with open(outcsv, 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    def agg(sel):
        n = len(sel)
        return (n,
                sum(1 for r in sel if r['llm'] == r['truth']),
                sum(1 for r in sel if r['tool'] == r['truth']),
                sum(1 for r in sel if r['llm'] == r['tool']),
                sum(1 for r in sel if r['llm'] == 'unparsed'))

    print(f"{'model':24s} {'cond':6s} {'group':8s} {'n':>3} {'llm=truth':>9} "
          f"{'tool=truth':>10} {'llm=tool':>8} {'unparsed':>8}")
    for model in sorted({r['model'] for r in rows}):
        for cond in ('named', 'anon'):
            for group in ('name', 'review', 'witness', 'exploit'):
                sel = [r for r in rows if r['model'] == model and r['cond'] == cond
                       and r['group'] == group]
                if not sel:
                    continue
                n, lt, tt, lo, unp = agg(sel)
                print(f"{model:24s} {cond:6s} {group:8s} {n:3d} {lt:9d} {tt:10d} {lo:8d} {unp:8d}")
    print(f"\nper-item rows written to {outcsv}")


if __name__ == '__main__':
    main()

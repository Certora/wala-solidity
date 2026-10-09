#!/usr/bin/env python3
"""Build the LLM-comparison manifest: every question item with its ground truth,
its provenance, RoundAbout's verdict, and the source file to show the model.

Ground-truth classes (see the eval's correctness subsection):
  name      the developer-declared direction in the function's own name (36 fns)
  review    a security-review ruling recorded as a regression test
  witness   machine-proven Indeterminate (differential-harness witnesses both sides)
  exploit   adjudicated by a historical exploit's post-mortem

Honesty notes, by design:
  - labels are fixed HERE, before any model is queried, and committed;
  - items whose ground truth is coarse or contested are excluded rather than guessed;
  - RoundAbout's verdict is recorded per item so the scorer cannot conflate
    "agrees with ground truth" and "agrees with the tool".

Usage: build_manifest.py <json-dir> <roots-extract> <out.json>
  json-dir: the per-test result JSONs (for methodPosition -> source file)
"""
import json
import os
import re
import sys

UP = re.compile(r'(Up|Ceil)$|^ceilDiv$|^divUp$|^mulDivUp$|^rayDivUp$|^mulUp$')
DOWN = re.compile(r'(Down|Floor|Truncate|Truncated)$|^divDown$|^mulDivDown$|^rayDivDown$|^mulDown$')
EXCL = re.compile(r'RoundsUp$|WithFloor$|Lookup$|^getDebtCeiling$')

jdir, roots, outp = sys.argv[1], sys.argv[2], sys.argv[3]

# (test, function) -> (file, roundabout verdict) from the result JSONs' roots
meta = {}
for f in sorted(os.listdir(jdir)):
    if not f.endswith('.json'):
        continue
    test = f[:-5].rsplit('_', 1)[0]
    doc = json.load(open(os.path.join(jdir, f)))
    for g in doc.get('graphs', []):
        md = (g.get('nodes', {}).get('0') or {}).get('metadata') or {}
        m = re.search(r'<Code body of function ([^>]+)>', md.get('method') or '')
        pos = md.get('methodPosition') or ''
        if not m or ':' not in pos:
            continue
        fn, path = m.group(1), pos.rsplit(':', 1)[0]
        ret = str(md.get('return'))
        if ret.startswith('{'):  # tuple returns are pre-rendered strings in the JSON
            vals = set(re.findall(r'>=(\w+)', ret))
            ret = vals.pop() if len(vals) == 1 else 'tuple'  # uniform tuple = that verdict
        # all-exact contexts only, consistent with the name check
        if all(p.get('rounding') == 'Neither' for p in md.get('parameters', [])):
            meta.setdefault((test, fn), (path, ret))

items = []

# class 1: developer-declared names (mirrors name_agreement.py exactly)
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from protocols import PROT  # noqa: E402
for (test, fn), (path, ret) in sorted(meta.items()):
    if test not in PROT:
        continue
    base = fn.lstrip('_')
    if EXCL.search(base):
        continue
    label = 'Up' if UP.search(base) else ('Down' if DOWN.search(base) else None)
    if label and ret != 'tuple':
        items.append(dict(id=f"name-{test}-{fn}", group='name', test=test, fn=fn,
                          label=label, roundabout=ret, file=path))

# classes 2-4: fixed by hand, labels from the provenance stated in the paper
def fixed(id_, group, test, fn, label, note):
    got = meta.get((test, fn))
    if not got:
        print(f"WARNING: no root entry for {test}.{fn}; skipped", file=sys.stderr)
        return
    items.append(dict(id=id_, group=group, test=test, fn=fn, label=label,
                      roundabout=got[1], file=got[0], note=note))

fixed('review-morpho-liquidate', 'review', 'TestMorphoMidnight', 'liquidate',
      'Indeterminate', 'security review: incentive factor branches on a rounded comparison')
fixed('witness-cozy-convert', 'witness', 'TestCozyEuler', 'convertToReserveAssetAmount',
      'Indeterminate', 'harness witnesses on both sides (2 above / 857 below / 159 equal)')
fixed('witness-balancer-onswap', 'witness', 'TestBalancerStablePool4f189ea1', 'onSwap',
      'Indeterminate', 'harness witnesses on both sides (1640 above / 13 below / 238 equal)')

print(json.dumps({'n': len(items)}, indent=0))
json.dump(items, open(outp, 'w'), indent=1)
print(f"wrote {outp}: {len(items)} items "
      f"({sum(1 for i in items if i['group']=='name')} name, "
      f"{sum(1 for i in items if i['group']=='review')} review, "
      f"{sum(1 for i in items if i['group']=='witness')} witness)")
print("NOTE: exploit-class items come from the case-study fixtures and are added by "
      "add_exploit_items.py, which runs the tool on those fixtures first.")

#!/usr/bin/env python3
"""Score the rename study: RoundAbout and each model, on named and renamed code.

Every answer to a question whose label is a direction falls in exactly one class:
  correct          the label
  refused          Indeterminate
  wrong direction  any other definite answer (the opposite direction, or Exact)
  unparsed         no verdict (RoundAbout: no single verdict for the function)

Counts are given per question (55: one agent run each) and per function body (38: a body
counts in a class only when every question on it does; bodies whose questions landed in
different classes are counted separately as "split"). Per question it also reports how
many answers changed between the named and the renamed code, and RoundAbout's named vs
renamed identity.

Run from eval-artifacts/:
  score_rename.py llm-agent/items.json llm-agent/pure-selection.json \
      llm-agent/roundabout-rename llm-agent/transcripts <out.json> [--partial]
"""
import collections
import glob
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from run_agent import parse_verdict  # noqa: E402

CLASSES = ('correct', 'refused', 'wrong direction', 'unparsed')
VERSIONS = ('named', 'renamed')
AGENT_DIR = {'named': 'named', 'renamed': 'anon'}  # run_agent.py's condition names


def classify(label, answer):
    if answer is None:
        return 'unparsed'
    if answer == label:
        return 'correct'
    return 'refused' if answer == 'Indeterminate' else 'wrong direction'


def main():
    items_p, sel_p, ra_dir, tr_dir, outp = sys.argv[1:6]
    partial = '--partial' in sys.argv
    items = {i['id']: i for i in json.load(open(items_p))}
    sel = json.load(open(sel_p))
    ids = sel['ids']

    ra = {}
    for f in sorted(glob.glob(os.path.join(ra_dir, 'results-*.json'))):
        for q in json.load(open(f))['questions']:
            ra[q['id']] = q
    missing_ra = [i for i in ids if i not in ra]
    if missing_ra and not partial:
        raise SystemExit(f"RoundAbout results missing for {len(missing_ra)} questions")

    models = sorted(d for d in os.listdir(tr_dir) if os.path.isdir(os.path.join(tr_dir, d)))
    answers = collections.defaultdict(dict)  # (system, version) -> id -> verdict or None
    for i in ids:
        q = ra.get(i)
        for v in VERSIONS if q else ():
            answers[('RoundAbout', v)][i] = q[v][0] if len(q[v]) == 1 else None
        for m in models:
            for v in VERSIONS:
                p = os.path.join(tr_dir, m, AGENT_DIR[v], i + '.jsonl')
                if not os.path.isfile(p):
                    if not partial:
                        raise SystemExit(f"missing transcript {p}")
                    continue
                answers[(m, v)][i] = parse_verdict(open(p).read())[0]

    out = dict(questions=len(ids), bodies=len(sel['bodies']), systems={})
    print(f"{len(ids)} questions, {len(sel['bodies'])} function bodies")
    for system in ['RoundAbout'] + models:
        out['systems'][system] = {}
        for v in VERSIONS:
            got = answers[(system, v)]
            cls = {i: classify(items[i]['label'], got[i]) for i in ids if i in got}
            per_q = collections.Counter(cls.values())
            per_body, split = collections.Counter(), 0
            for b in sel['bodies']:
                cs = {cls[q] for q in b['questions'] if q in cls}
                if len(cs) == 1:
                    per_body[cs.pop()] += 1
                elif cs:
                    split += 1
            out['systems'][system][v] = dict(
                answered=len(cls),
                per_question={c: per_q.get(c, 0) for c in CLASSES},
                per_body={c: per_body.get(c, 0) for c in CLASSES}, per_body_split=split)
            print(f"{system:16s} {v:8s} n={len(cls):2d}  per question "
                  + '  '.join(f"{c}={per_q.get(c, 0)}" for c in CLASSES)
                  + f"   | per body " + '  '.join(f"{c}={per_body.get(c, 0)}" for c in CLASSES)
                  + f"  split={split}")
        both = [i for i in ids if i in answers[(system, 'named')] and i in answers[(system, 'renamed')]]
        changed = [i for i in both if answers[(system, 'named')][i] != answers[(system, 'renamed')][i]]
        out['systems'][system]['named_vs_renamed'] = dict(
            compared=len(both), identical=len(both) - len(changed),
            changed=[dict(id=i, named=answers[(system, 'named')][i],
                          renamed=answers[(system, 'renamed')][i]) for i in changed])
        print(f"{'':16s} named vs renamed: {len(both) - len(changed)}/{len(both)} answers identical")
    json.dump(out, open(outp, 'w'), indent=1)
    print(f"wrote {outp}")


if __name__ == '__main__':
    main()

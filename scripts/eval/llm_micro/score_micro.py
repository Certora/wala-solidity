#!/usr/bin/env python3
"""Score each model on the microbenchmarks against RoundAbout's verdicts (user decision,
2026-10-13: the reference is the tool's result, so this measures agreement, not accuracy).

Per answer: agree (RoundAbout's verdict), refused (Indeterminate where RoundAbout gives a
direction or Exact), stronger (a definite answer where RoundAbout says Indeterminate),
different (another definite answer), unparsed (no verdict on the last line). Also listed:
the questions where RoundAbout's verdict contradicts the hand label in labels.json (known
tool errors), with each model's answer there; per-fixture counts; cost from the transcripts.

Run from eval-artifacts/:
  python3 ../scripts/eval/llm_micro/score_micro.py llm-micro/items.json \
      llm-micro/roundabout-<commit>/verdicts.json llm-micro/transcripts llm-micro/scores.json
"""
import collections
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'llm_agent'))
from run_agent import parse_verdict  # noqa: E402

OUTCOMES = ['agree', 'refused', 'stronger', 'different', 'unparsed', 'missing']


def outcome(ref, v):
    if v == 'MISSING':
        return 'missing'
    if v is None:
        return 'unparsed'
    if v == ref:
        return 'agree'
    if v == 'Indeterminate':
        return 'refused'
    return 'stronger' if ref == 'Indeterminate' else 'different'


def main():
    items_path, ra_path, transcripts, out = sys.argv[1:5]
    items = json.load(open(items_path))
    ra = json.load(open(ra_path))
    answers = {'RoundAbout': {i['id']: ra['verdicts'][i['id']]['verdict'] for i in items}}
    cost = {}
    for model in sorted(os.listdir(transcripts)) if os.path.isdir(transcripts) else []:
        answers[model], cost[model] = {}, 0.0
        for i in items:
            p = os.path.join(transcripts, model, i['id'] + '.jsonl')
            if not os.path.isfile(p):
                answers[model][i['id']] = 'MISSING'
                continue
            v, result = parse_verdict(open(p).read())
            answers[model][i['id']] = v
            cost[model] += (result or {}).get('total_cost_usd', 0) or 0
    ref = answers['RoundAbout']
    models = [m for m in answers if m != 'RoundAbout']
    table, per_fixture = {}, {}
    for m in models:
        f = collections.defaultdict(collections.Counter)
        t = collections.Counter()
        for i in items:
            o = outcome(ref[i['id']], answers[m][i['id']])
            t[o] += 1
            f[i['fixture']][o] += 1
        table[m] = {o: t[o] for o in OUTCOMES}
        per_fixture[m] = {k: dict(c) for k, c in sorted(f.items())}
    known = [dict(id=i['id'], label=i['label'], **{t: answers[t][i['id']] for t in answers})
             for i in items if ref[i['id']] != i['label']]
    rows = [dict(id=i['id'], **{t: answers[t][i['id']] for t in answers}) for i in items]
    json.dump(dict(questions=len(items), reference='RoundAbout', jar_sha256=ra['jar_sha256'],
                   table=table, known_tool_errors=known, cost_usd=cost, per_fixture=per_fixture,
                   rows=rows), open(out, 'w'), indent=1)
    print(f'{len(items)} questions; reference RoundAbout, jar {ra["jar_sha256"][:12]}')
    for m in models:
        print(f'{m:18s} ' + '  '.join(f'{o} {table[m][o]}' for o in OUTCOMES) + f'  cost ${cost[m]:.2f}')
    print('known tool errors (RoundAbout verdict != hand label):')
    for k in known:
        print('  ' + '  '.join(f'{a}={b}' for a, b in k.items()))


if __name__ == '__main__':
    main()

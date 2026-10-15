#!/usr/bin/env python3
"""Score the unified study: each model's answers, per set (name, tricky, micro) and version
(named, renamed), against RoundAbout's verdict; plus, per model, how many answers stay the same
from the named to the renamed version, and the cost at list prices.

Outcomes as in llm_micro/score_micro.py: agree, refused (Indeterminate where RoundAbout gives a
direction or Exact), stronger (a definite answer where RoundAbout says Indeterminate), different,
unparsed, missing.

Run from eval-artifacts/:
  python3 ../scripts/eval/llm_unified/score_unified.py llm-unified/items.json llm-unified/answers \
      llm-unified/scores.json
"""
import collections
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'llm_micro'))
from run_plain import parse_verdict  # noqa: E402
from score_micro import OUTCOMES, outcome  # noqa: E402
from score_plain import PRICE  # noqa: E402

SETS = ('name', 'tricky', 'micro')
CONDITIONS = ('named', 'renamed')


def answer(path):
    if not os.path.isfile(path):
        return 'MISSING', 0, 0
    r = json.load(open(path))['response']
    text = ''.join(b.get('text', '') for b in r['content'] if b['type'] == 'text')
    v = parse_verdict(text) if r['stop_reason'] == 'end_turn' else None
    return v, r['usage']['input_tokens'], r['usage']['output_tokens']


def main():
    items_path, answers, out = sys.argv[1:4]
    items = json.load(open(items_path))
    models = sorted(os.listdir(answers))
    table, stable, cost, rows = {}, {}, {}, []
    ans = {}
    for m in models:
        tin = tout = 0
        for i in items:
            for c in CONDITIONS:
                v, a, b = answer(os.path.join(answers, m, c, i['id'] + '.json'))
                ans[m, c, i['id']] = v
                tin, tout = tin + a, tout + b
        cost[m] = (tin * PRICE[m][0] + tout * PRICE[m][1]) / 1e6
        table[m] = {s: {c: dict(collections.Counter(outcome(i['reference'], ans[m, c, i['id']])
                                                    for i in items if i['set'] == s))
                        for c in CONDITIONS} for s in SETS}
        stable[m] = {s: sum(ans[m, 'named', i['id']] == ans[m, 'renamed', i['id']]
                            for i in items if i['set'] == s) for s in SETS}
    for i in items:
        rows.append(dict(id=i['id'], set=i['set'], RoundAbout=i['reference'],
                         **{f'{m} {c}': ans[m, c, i['id']] for m in models for c in CONDITIONS}))
    n = collections.Counter(i['set'] for i in items)
    json.dump(dict(questions=dict(n), table=table, same_named_renamed=stable, cost_usd_list_price=cost,
                   rows=rows), open(out, 'w'), indent=1)
    print(f'questions {dict(n)}')
    for m in models:
        for s in SETS:
            for c in CONDITIONS:
                t = table[m][s][c]
                print(f'{m:16s} {s:6s} {c:7s} agree {t.get("agree", 0):3d}/{n[s]:3d}  ' +
                      '  '.join(f'{o} {t[o]}' for o in OUTCOMES if o != 'agree' and t.get(o)))
            print(f'{"":16s} {s:6s} same answer named vs renamed: {stable[m][s]}/{n[s]}')
        print(f'{m}: cost ${cost[m]:.2f}')


if __name__ == '__main__':
    main()

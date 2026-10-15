#!/usr/bin/env python3
"""Score the plain-prompt answers (run_plain.py) against RoundAbout's verdicts, with the same
outcomes and exclusions as score_micro.py, and compare each answer with the same model's
answer in the agent study.

Run from eval-artifacts/:
  python3 ../scripts/eval/llm_micro/score_plain.py llm-micro/items.json \
      llm-micro/roundabout-<commit>/verdicts.json llm-micro/plain llm-micro/scores.json \
      llm-micro/scores-plain.json
"""
import collections
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from run_plain import parse_verdict  # noqa: E402
from score_micro import EXCLUDED, OUTCOMES, outcome  # noqa: E402

# list prices, USD per million tokens (input, output), Claude API skill table cached 2026-09-25
PRICE = {'claude-opus-5-5': (4.0, 20.0), 'claude-sonnet-5': (2.0, 10.0)}


def main():
    items_path, ra_path, plain, agent_scores, out = sys.argv[1:6]
    items = [i for i in json.load(open(items_path)) if i['id'] not in EXCLUDED]
    ref = {k: v['verdict'] for k, v in json.load(open(ra_path))['verdicts'].items()}
    agent = {r['id']: r for r in json.load(open(agent_scores))['rows']}
    table, rows, cost, tokens = {}, {i['id']: dict(id=i['id'], RoundAbout=ref[i['id']]) for i in items}, {}, {}
    same_as_agent = {}
    for model in sorted(os.listdir(plain)):
        t, tin, tout, same = collections.Counter(), 0, 0, 0
        for i in items:
            p = os.path.join(plain, model, i['id'] + '.json')
            if not os.path.isfile(p):
                v = 'MISSING'
            else:
                r = json.load(open(p))['response']
                text = ''.join(b.get('text', '') for b in r['content'] if b['type'] == 'text')
                v = parse_verdict(text) if r['stop_reason'] == 'end_turn' else None
                tin += r['usage']['input_tokens']
                tout += r['usage']['output_tokens']
            t[outcome(ref[i['id']], v)] += 1
            rows[i['id']][model] = v
            rows[i['id']][model + ' (agent)'] = agent[i['id']].get(model)
            same += v == agent[i['id']].get(model)
        table[model] = {o: t[o] for o in OUTCOMES}
        tokens[model] = dict(input=tin, output=tout)
        cost[model] = (tin * PRICE[model][0] + tout * PRICE[model][1]) / 1e6
        same_as_agent[model] = same
    json.dump(dict(questions=len(items), excluded=sorted(EXCLUDED), reference='RoundAbout', table=table,
                   same_answer_as_agent_study=same_as_agent, tokens=tokens, cost_usd_list_price=cost,
                   rows=list(rows.values())), open(out, 'w'), indent=1)
    print(f'{len(items)} questions ({len(EXCLUDED)} excluded); reference RoundAbout')
    for m in table:
        print(f'{m:18s} ' + '  '.join(f'{o} {table[m][o]}' for o in OUTCOMES)
              + f'  same as agent study {same_as_agent[m]}/{len(items)}  ~${cost[m]:.2f}')


if __name__ == '__main__':
    main()

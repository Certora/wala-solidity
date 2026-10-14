#!/usr/bin/env python3
"""Ask each microbenchmark question with the user's earlier plain prompt: one Messages API call
per (question, model), no tools, no system prompt, the anonymized fixture code pasted inline.

The wording is the user's earlier prompt ("Consider the returned values we would get if we had
Real semantics..."), in the singular. Added only: the contract the function belongs to, which
return value a tuple question means, the SpecLink deployment links (as in the agent study),
and a last-line JSON answer format. Effort is set to high for both models (Opus 5.5 defaults
to medium); thinking is the API default (adaptive). No refusal fallback, so every answer
comes from the model named. Responses are saved write-once, never regenerated.

Run from eval-artifacts/:
  python3 ../scripts/eval/llm_micro/run_plain.py llm-micro/items.json llm-micro/workspaces \
      llm-micro/plain --models claude-opus-5-5 claude-sonnet-5 [--fixtures F ...] [--dry-run]
  add --assumptions (output dir llm-micro/plain-assumptions) for the variant with the agent
  prompt's assumptions; add --assumptions --definitions (llm-micro/plain-defs) to also define
  the four classes
"""
import argparse
import json
import os
import sys

import anthropic

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from score_micro import EXCLUDED  # noqa: E402

EFFORT = 'high'
MAX_TOKENS = 16000
ORDINAL = ['first', 'second', 'third', 'fourth']
VERDICTS = {'Exact', 'Down', 'Up', 'Indeterminate'}

# --assumptions: the agent prompt's assumptions (run_agent.PROMPT), word for word except that
# "code outside /work" becomes "code not shown here"
ASSUMPTIONS = """Assumptions (apply these; do not answer a different question):
- All parameters are exact.
- Values read from storage, and values returned by calls to code not shown
  here, are exact.
- Arithmetic never overflows, and all values are non-negative.
- Only inputs on which the function returns normally count.

"""
# --nonzero-divisors adds this line to the assumptions (user, 2026-10-14); Yul's div(x, 0) returns
# 0 rather than reverting, so the line states the assumption instead of deriving it
NONZERO = "- Every divisor is non-zero (division by zero reverts in Solidity).\n"


def code_block(fixture, workspaces):
    d = os.path.join(workspaces, fixture, 'anon')
    files = sorted(os.listdir(d))
    parts = []
    for f in files:
        text = open(os.path.join(d, f)).read().strip('\n')
        parts.append((f'// {f}\n' if len(files) > 1 else '') + text)
    return '```solidity\n' + '\n\n'.join(parts) + '\n```'


# --definitions: the agent prompt's four classes (run_agent.PROMPT), compared against the
# user's "Real semantics" value instead of the agent prompt's ACTUAL/INTENDED pair
DEFINITIONS = """Classify the result over all inputs that satisfy these assumptions, comparing it
with the value it would have under Real semantics:
- Exact: the result always equals the Real-semantics value
- Down: the result is always less than or equal to the Real-semantics value
- Up: the result is always greater than or equal to the Real-semantics value
- Indeterminate: the result is above the Real-semantics value for some inputs
  and below it for others

"""


def prompt_for(item, workspaces, assumptions=False, definitions=False, nonzero=False):
    a = item['anon']
    fn = a['signature'].split('(')[0]
    subject = f'the result of {fn}'
    if item['component'] is not None:
        subject = (f'the {ORDINAL[item["component"]]} of the {item["n_returns"]} values '
                   f'returned by {fn}')
    p = (f'Consider the following function {fn} in contract {a["contract"]}. It is from a Solidity '
         f'smart contract. For simplicity, we are only showing the core code without additional '
         f'boilerplate.\n\n'
         f'Consider the returned value we would get if we had Real semantics. Compared to that, '
         f'in what direction do you think {subject} would be rounded in?\n\n'
         + code_block(item['fixture'], workspaces) + '\n\n')
    if a['deployment']:
        where = ', '.join(f'`{k}` is a `{v}` contract' for k, v in a['deployment'].items())
        p += f'In the deployed system, {where}; `engines` has no other entries.\n\n'
    if assumptions:
        p += ASSUMPTIONS.replace('count.\n\n', 'count.\n' + NONZERO + '\n') if nonzero else ASSUMPTIONS
    if definitions:
        p += DEFINITIONS
    p += ('End your answer with one JSON object on its last line:\n'
          '{"verdict": "Exact" | "Down" | "Up" | "Indeterminate"}')
    return p


def parse_verdict(text):
    # the last non-blank line, ignoring a closing code fence around it (Opus wrapped the JSON
    # in ```json ... ``` on one of the 14 pilot answers, CeilingIdioms.ceilDiv)
    last = [ln for ln in text.strip().splitlines() if ln.strip() and not ln.strip().startswith('```')]
    try:
        v = json.loads(last[-1]).get('verdict') if last else None
    except (ValueError, AttributeError):
        v = None
    return v if v in VERDICTS else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('items')
    ap.add_argument('workspaces')
    ap.add_argument('out')
    ap.add_argument('--models', nargs='+', required=True)
    ap.add_argument('--fixtures', nargs='*')
    ap.add_argument('--assumptions', action='store_true', help='add the agent prompt\'s assumptions')
    ap.add_argument('--definitions', action='store_true', help='add the four classes\' definitions')
    ap.add_argument('--nonzero-divisors', action='store_true', help='also assume every divisor is non-zero')
    ap.add_argument('--dry-run', action='store_true')
    a = ap.parse_args()
    items = [i for i in json.load(open(a.items)) if i['id'] not in EXCLUDED]
    if a.fixtures:
        items = [i for i in items if i['fixture'] in set(a.fixtures)]
    if a.dry_run:
        for i in items:
            print(f"===== {i['id']}\n{prompt_for(i, a.workspaces, a.assumptions, a.definitions, a.nonzero_divisors)}\n")
        return
    client = anthropic.Anthropic()
    for item in items:
        prompt = prompt_for(item, a.workspaces, a.assumptions, a.definitions, a.nonzero_divisors)
        for model in a.models:
            d = os.path.join(a.out, model)
            os.makedirs(d, exist_ok=True)
            path = os.path.join(d, item['id'] + '.json')
            if os.path.isfile(path):
                continue
            request = dict(model=model, max_tokens=MAX_TOKENS,
                           messages=[{'role': 'user', 'content': prompt}])
            r = client.messages.create(**request, extra_body={'output_config': {'effort': EFFORT}})
            text = ''.join(b.text for b in r.content if b.type == 'text')
            with open(path, 'w') as f:
                json.dump(dict(request=dict(request, output_config={'effort': EFFORT}),
                               response=r.model_dump(mode='json')), f, indent=1)
            v = parse_verdict(text) if r.stop_reason == 'end_turn' else None
            print(f'{model:16s} {v or "UNPARSED(" + str(r.stop_reason) + ")":13s} '
                  f'in={r.usage.input_tokens} out={r.usage.output_tokens} {item["id"]}')


if __name__ == '__main__':
    main()

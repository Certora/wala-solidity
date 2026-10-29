#!/usr/bin/env python3
"""Ask every unified-study question (build_unified.py) in both versions: one direct Messages API
call per (question, version, model), no tools, no system prompt, the code pasted inline.

The prompt is run_plain's final prompt (the user's wording, then the code, the ASSUMPTIONS and
the DEFINITIONS, then the last-line JSON format), with no hint about rounding patterns. A
multi-file question shows each file under a `// <path>` line, in one code block. Effort high,
thinking at the API default, no refusal fallback. Responses are streamed (long answers dropped
the connection when not streamed) and saved write-once; a call that fails is retried, never
saved half-done.

Run from eval-artifacts/:
  python3 ../scripts/eval/llm_unified/run_unified.py llm-unified/items.json llm-unified/workspaces \
      llm-unified/answers --models claude-opus-5-5 claude-sonnet-5 [--jobs 6] [--only ID ...] [--dry-run]
"""
import argparse
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import anthropic

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'llm_micro'))
from run_plain import ASSUMPTIONS, DEFINITIONS, EFFORT, MAX_TOKENS, ORDINAL, parse_verdict  # noqa: E402

CONDITIONS = ('named', 'renamed')


def code_block(item, cond, workspaces):
    d = os.path.join(workspaces, item['codebase'], cond)
    files = [item['renamed_files'][f] if cond == 'renamed' and 'renamed_files' in item else f
             for f in item['files']]
    parts = []
    for f in sorted(files):
        text = open(os.path.join(d, f)).read().strip('\n')
        parts.append((f'// {f}\n' if len(files) > 1 else '') + text)
    return '```solidity\n' + '\n\n'.join(parts) + '\n```'


def prompt_for(item, cond, workspaces):
    c = item[cond]
    fn = c['signature'].split('(')[0]
    subject = f'the result of {fn}'
    if item['component'] is not None:
        subject = f'the {ORDINAL[item["component"]]} of the {item["n_returns"]} values returned by {fn}'
    p = (f'Consider the following function {fn} in contract {c["contract"]}. It is from a Solidity '
         f'smart contract. For simplicity, we are only showing the core code without additional '
         f'boilerplate.\n\n'
         f'Consider the returned value we would get if we had Real semantics. Compared to that, '
         f'in what direction do you think {subject} would be rounded in?\n\n'
         + code_block(item, cond, workspaces) + '\n\n')
    if c['deployment']:
        where = ', '.join(f'`{k}` is a `{v}` contract' for k, v in c['deployment'].items())
        p += f'In the deployed system, {where}; `engines` has no other entries.\n\n'
    p += ASSUMPTIONS + DEFINITIONS
    p += ('End your answer with one JSON object on its last line:\n'
          '{"verdict": "Exact" | "Down" | "Up" | "Indeterminate"}')
    return p


def ask(client, model, prompt, path, label):
    request = dict(model=model, max_tokens=MAX_TOKENS, messages=[{'role': 'user', 'content': prompt}])
    for attempt in range(1, 9):
        try:
            with client.messages.stream(**request, extra_body={'output_config': {'effort': EFFORT}}) as s:
                r = s.get_final_message()
            break
        except (anthropic.APIConnectionError, anthropic.RateLimitError, anthropic.InternalServerError) as e:
            print(f'retry {attempt} {label}: {type(e).__name__}', flush=True)
            time.sleep(min(120, 10 * attempt))
    else:
        print(f'GAVE UP {label}', flush=True)
        return
    with open(path + '.tmp', 'w') as f:
        json.dump(dict(request=dict(request, output_config={'effort': EFFORT}),
                       response=r.model_dump(mode='json')), f, indent=1)
    os.replace(path + '.tmp', path)
    text = ''.join(b.text for b in r.content if b.type == 'text')
    v = parse_verdict(text) if r.stop_reason == 'end_turn' else None
    print(f'{v or "UNPARSED(" + str(r.stop_reason) + ")":13s} in={r.usage.input_tokens} '
          f'out={r.usage.output_tokens} {label}', flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('items')
    ap.add_argument('workspaces')
    ap.add_argument('out')
    ap.add_argument('--models', nargs='+', required=True)
    ap.add_argument('--jobs', type=int, default=6)
    ap.add_argument('--only', nargs='*')
    ap.add_argument('--dry-run', action='store_true')
    a = ap.parse_args()
    items = json.load(open(a.items))
    if a.only:
        items = [i for i in items if i['id'] in set(a.only)]
    if a.dry_run:
        for i in items:
            for cond in CONDITIONS:
                print(f"===== {i['id']} [{cond}]\n{prompt_for(i, cond, a.workspaces)}\n")
        return
    client = anthropic.Anthropic()
    todo = []
    for i in items:
        for cond in CONDITIONS:
            prompt = prompt_for(i, cond, a.workspaces)
            for model in a.models:
                d = os.path.join(a.out, model, cond)
                os.makedirs(d, exist_ok=True)
                path = os.path.join(d, i['id'] + '.json')
                if not os.path.isfile(path):
                    todo.append((model, prompt, path, f'{model} {cond} {i["id"]}'))
    print(f'{len(todo)} calls to make', flush=True)
    with ThreadPoolExecutor(a.jobs) as ex:
        list(ex.map(lambda t: ask(client, *t), todo))
    missing = sum(1 for _, _, p, _ in todo if not os.path.isfile(p))
    print(f'done; missing {missing}', flush=True)
    if missing:
        raise SystemExit(1)


if __name__ == '__main__':
    main()

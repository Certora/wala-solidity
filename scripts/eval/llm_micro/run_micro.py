#!/usr/bin/env python3
"""Run the agent baseline on the microbenchmarks: one fresh container per (question, model).

Same sandbox, image, tools and prompt as the rename study (llm_agent/run_agent.py), on the
anonymized fixture workspaces only. The prompt changes in two places, and only for the
questions that need it:
  - a function returning several values: the task names which one by position only (a name
    can mislead: `return (y, x)` puts y's value in the slot named x), since RoundAbout
    reports each component separately;
  - the two SpecLink fixtures: which contract each `engines` entry holds, which RoundAbout
    reads from the spec's links.
Transcripts are write-once, as in the rename study.

Run from eval-artifacts/:
  python3 ../scripts/eval/llm_micro/run_micro.py llm-micro/items.json llm-micro/workspaces \
      llm-micro/transcripts --models claude-opus-5-5 claude-sonnet-5 [--only ID ...] [--dry-run]
"""
import argparse
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'llm_agent'))
from run_agent import IMAGE, PROMPT, TOOLS, parse_verdict  # noqa: E402

TASK = "Task: decide the rounding direction of one function's return value."
ORDINAL = ['first', 'second', 'third', 'fourth']


def prompt_for(item):
    a = item['anon']
    p = PROMPT.format(signature=a['signature'], contract=a['contract'], file=a['file'], line=item['line'])
    assert TASK in p
    if item['component'] is not None:
        p = p.replace(TASK, f"Task: decide the rounding direction of one of a function's "
                            f"{item['n_returns']} return values: the {ORDINAL[item['component']]} "
                            f"one in its `returns (...)` list.")
    if a['deployment']:
        where = ', '.join(f'`{k}` is a `{v}` contract' for k, v in a['deployment'].items())
        anchor = f"defined in `{a['file']}` at line {item['line']}.\n"
        assert anchor in p
        p = p.replace(anchor, anchor + f"\nIn the deployed system, {where}; `engines` has no "
                                       f"other entries.\n")
    return p


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('items')
    ap.add_argument('workspaces')
    ap.add_argument('out')
    ap.add_argument('--models', nargs='+', required=True)
    ap.add_argument('--only', nargs='*')
    ap.add_argument('--max-turns', type=int, default=60)
    ap.add_argument('--dry-run', action='store_true', help='print the prompts, run nothing')
    a = ap.parse_args()
    items = json.load(open(a.items))
    if a.only:
        items = [i for i in items if i['id'] in set(a.only)]
    if a.dry_run:
        for item in items:
            print(f"===== {item['id']}  (workspace {item['fixture']}/anon)\n{prompt_for(item)}")
        return
    if not os.environ.get('ANTHROPIC_API_KEY'):
        raise SystemExit('ANTHROPIC_API_KEY is not set')
    done = failed = skipped = 0
    t0 = time.time()
    for item in items:
        prompt = prompt_for(item)
        work = os.path.abspath(os.path.join(a.workspaces, item['fixture'], 'anon'))
        for model in a.models:
            d = os.path.join(a.out, model)
            os.makedirs(d, exist_ok=True)
            path = os.path.join(d, item['id'] + '.jsonl')
            if os.path.isfile(path) and os.path.getsize(path) > 0:
                skipped += 1
                continue
            cmd = ['docker', 'run', '--rm', '-i', '-e', 'ANTHROPIC_API_KEY',
                   '-v', f'{work}:/work:ro', IMAGE,
                   '-p', '--model', model, '--max-turns', str(a.max_turns),
                   '--tools', TOOLS, '--allowedTools', TOOLS, '--strict-mcp-config',
                   '--output-format', 'stream-json', '--verbose']
            r = subprocess.run(cmd, input=prompt, capture_output=True, text=True, timeout=1800)
            verdict, result = parse_verdict(r.stdout)
            if r.returncode != 0 or result is None:
                failed += 1
                with open(path + '.err', 'w') as f:
                    f.write(f'rc={r.returncode}\n--- stderr ---\n{r.stderr}\n--- stdout ---\n{r.stdout}')
                print(f'FAIL {model} {item["id"]}')
                continue
            with open(path, 'w') as f:
                f.write(json.dumps({'type': 'prompt', 'text': prompt, 'cmd': cmd}) + '\n')
                f.write(r.stdout)
            done += 1
            print(f'ok   {model:16s} {verdict or "UNPARSED":13s} turns={result.get("num_turns")} '
                  f'cost=${result.get("total_cost_usd", 0):.3f} {item["id"]}  [{int(time.time() - t0)}s]')
    print(f'\ncompleted {done}, skipped (existing) {skipped}, failed {failed}')
    if failed:
        raise SystemExit(1)


if __name__ == '__main__':
    main()

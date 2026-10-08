#!/usr/bin/env python3
"""Run the agent baseline: one fresh container per (question, condition, model).

Each container holds Claude Code and nothing else, sees only one code base mounted
read-only at /work, and has exactly three tools - Read, Grep and Glob - set with --tools,
so no shell, edit, web or subagent tool exists for it. We do not use --bare: it sets
CLAUDE_CODE_SIMPLE, which removes Grep and Glob. The container has no settings, memory,
CLAUDE.md, plugins, MCP servers or git, so the agent's context is Claude Code's generic
instructions plus this prompt (checked with a probe, 2026-10-08); with no write tool and a
discarded container, nothing carries between questions. The network stays on only
because the agent must reach the API; with no shell and no web tools, nothing inside the
container can use it.

Every run's full event stream (prompt, tool calls, final answer) is saved write-once:
an existing transcript is never regenerated, so answers cannot be silently re-rolled.

Run from eval-artifacts/:
  python3 ../scripts/eval/llm_agent/run_agent.py llm-agent/items.json llm-agent/workspaces \
      llm-agent/transcripts --models claude-opus-5-5 [...] [--conditions named anon] \
      [--only ID ...] [--max-turns 60]
Needs ANTHROPIC_API_KEY in the environment; it is passed to the container by name only.
"""
import argparse
import json
import os
import re
import subprocess
import time

IMAGE = 'roundabout-llm-agent:2.1.284'

PROMPT = """You are auditing Solidity code. The code is in /work (read-only).

Task: decide the rounding direction of one function's return value.

Function: `{signature}` in contract `{contract}`,
defined in `{file}` at line {line}.

Definitions:
- ACTUAL value: what the function returns when executed with Solidity's
  integer arithmetic, where division truncates.
- INTENDED value: what the same computation returns if every division is
  exact real-number division, with no truncation or rounding anywhere.
  Where the code uses an idiom to round on purpose (for example, adding one
  to a quotient when there is a remainder), the intended value is the exact
  quotient that the idiom approximates.

Assumptions (apply these; do not answer a different question):
- All parameters are exact.
- Values read from storage, and values returned by calls to code outside
  /work, are exact.
- Arithmetic never overflows, and all values are non-negative.
- Only inputs on which the function returns normally count.

Classify the function over all inputs that satisfy these assumptions:
- Exact: actual always equals intended
- Down: actual is always less than or equal to intended
- Up: actual is always greater than or equal to intended
- Indeterminate: actual is above intended for some inputs and below it for
  others

You may read any file under /work, including the code the function calls.
You cannot run code.

End your final message with exactly one JSON object, alone on its last line:
{{"verdict": "Exact" | "Down" | "Up" | "Indeterminate", "justification": "at most three sentences"}}
"""

TOOLS = 'Read,Grep,Glob'  # the ONLY tools that exist for the agent
VERDICTS = {'Exact', 'Down', 'Up', 'Indeterminate'}
IDENT = re.compile(r'[A-Za-z_$][A-Za-z0-9_$]*')


def slug(root):
    return root.replace('test/data/', '').replace('/', '__')


def anon_names(item, ws):
    """The contract and signature as they read in the anonymized workspace."""
    renames = json.load(open(os.path.join(ws, slug(item['root']), 'anon-map.json')))['renames']

    def sub(s):
        return IDENT.sub(lambda m: renames.get(m.group(0), m.group(0)), s)
    return sub(item['contract']), sub(item['signature'])


def parse_verdict(stream_text):
    """The verdict from the final result event; None if absent or malformed."""
    result = None
    for line in stream_text.splitlines():
        try:
            ev = json.loads(line)
        except ValueError:
            continue
        if ev.get('type') == 'result':
            result = ev
    if not result or not isinstance(result.get('result'), str):
        return None, result
    last = [ln for ln in result['result'].strip().splitlines() if ln.strip()]
    try:
        v = json.loads(last[-1]).get('verdict') if last else None
    except ValueError:
        v = None
    return (v if v in VERDICTS else None), result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('items')
    ap.add_argument('workspaces')
    ap.add_argument('out')
    ap.add_argument('--models', nargs='+', required=True)
    ap.add_argument('--conditions', nargs='+', default=['named', 'anon'])
    ap.add_argument('--only', nargs='*')
    ap.add_argument('--max-turns', type=int, default=60)
    a = ap.parse_args()
    if not os.environ.get('ANTHROPIC_API_KEY'):
        raise SystemExit('ANTHROPIC_API_KEY is not set')
    items = json.load(open(a.items))
    if a.only:
        items = [i for i in items if i['id'] in set(a.only)]
    done = failed = skipped = 0
    t0 = time.time()
    for item in items:
        for cond in a.conditions:
            contract, signature = ((item['contract'], item['signature']) if cond == 'named'
                                   else anon_names(item, a.workspaces))
            prompt = PROMPT.format(signature=signature, contract=contract,
                                   file=item['file'], line=item['line'])
            work = os.path.abspath(os.path.join(a.workspaces, slug(item['root']), cond))
            for model in a.models:
                d = os.path.join(a.out, model, cond)
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
                    print(f'FAIL {model} {cond} {item["id"]}')
                    continue
                with open(path, 'w') as f:
                    f.write(json.dumps({'type': 'prompt', 'text': prompt, 'cmd': cmd}) + '\n')
                    f.write(r.stdout)
                done += 1
                print(f'ok   {model:16s} {cond:5s} {verdict or "UNPARSED":13s} '
                      f'turns={result.get("num_turns")} cost=${result.get("total_cost_usd", 0):.3f} '
                      f'{item["id"]}  [{int(time.time() - t0)}s]')
    print(f'\ncompleted {done}, skipped (existing) {skipped}, failed {failed}')
    if failed:
        raise SystemExit(1)


if __name__ == '__main__':
    main()

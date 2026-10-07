#!/usr/bin/env python3
"""Run the LLM-comparison queries: one fresh, tool-less `claude -p` call per
(item, condition, model), transcript saved verbatim.

Validity notes:
  - --disallowedTools "*" denies every tool, so the model sees only the prompt;
  - each call is a fresh print-mode session: no shared context between items;
  - existing transcripts are skipped, so the run is resumable and a transcript,
    once written, is never regenerated (no silent re-rolling of answers).

Usage: run_queries.py <prompts-dir> <out-dir> <model> [<model> ...]
"""
import os
import subprocess
import sys
import time

prompts, outdir = sys.argv[1], sys.argv[2]
models = sys.argv[3:]
assert models, "give at least one model id"

jobs = []
for cond in ('named', 'anon'):
    for f in sorted(os.listdir(os.path.join(prompts, cond))):
        if f.endswith('.txt'):
            for m in models:
                jobs.append((m, cond, f[:-4]))

done = failed = skipped = 0
t0 = time.time()
for m, cond, iid in jobs:
    d = os.path.join(outdir, m, cond)
    os.makedirs(d, exist_ok=True)
    out = os.path.join(d, iid + '.txt')
    if os.path.isfile(out) and os.path.getsize(out) > 0:
        skipped += 1
        continue
    prompt = open(os.path.join(prompts, cond, iid + '.txt')).read()
    r = subprocess.run(['claude', '-p', '--model', m, '--disallowedTools', '*'],
                       input=prompt, capture_output=True, text=True, timeout=600)
    if r.returncode != 0 or not r.stdout.strip():
        failed += 1
        with open(out + '.err', 'w') as f:
            f.write(f"rc={r.returncode}\n--- stdout ---\n{r.stdout}\n--- stderr ---\n{r.stderr}")
        print(f"FAIL {m} {cond} {iid}")
        continue
    with open(out, 'w') as f:
        f.write(r.stdout)
    done += 1
    print(f"ok   {m} {cond} {iid}  [{int(time.time() - t0)}s]")

print(f"\ncompleted {done}, skipped(existing) {skipped}, failed {failed} of {len(jobs)}")
if failed:
    raise SystemExit(1)

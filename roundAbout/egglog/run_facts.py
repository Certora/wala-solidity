#!/usr/bin/env python3
"""Run rules.egg on the facts EgglogExport wrote and compare with today's phase 1.

Usage: run_facts.py FACTS_DIR [--egglog PATH] [--timeout SECONDS] [--csv OUT.csv]

Each facts file holds one call-graph node: a (let ...) per SSA value, a (Root v t)
per division, and TodayUp / TodayExact for what phase 1 decides today. The script
joins rules.egg, compare.egg and the facts into one program, runs egglog until
nothing changes (or the timeout), and classifies each division:

  same        egglog and today both read it as div(n, d, Up), with equal n and d
  differ      both find a round-up reading, but with different n or d
  egglog-only egglog finds a round-up reading, today does not
  today-only  today finds one, egglog does not
  literal     neither does (the division stays div(num, d, Down))
  exact       division by the constant 1

A division analyzed in several contexts appears in several files; the summary
counts call-graph nodes and also distinct division sites (source, method, value).
"""
import argparse
import csv
import os
import shutil
import subprocess
import sys
import tempfile
import time
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
RUN = """
(run-schedule (saturate (run)))
(print-function Intent :mode csv)
(print-function TodayUp :mode csv)
(print-function TodayExact :mode csv)
(print-function Agree :mode csv)
"""


def parse_header(text):
    meta, roots = {}, {}
    for line in text.splitlines():
        if line.startswith(';@root '):
            _, v, pos = line.split(' ', 2)
            roots[int(v)] = pos
        elif line.startswith(';@'):
            key, _, value = line[2:].partition(' ')
            meta[key] = value
    return meta, roots


def parse_rows(stdout):
    rows = {'Intent': [], 'TodayUp': [], 'TodayExact': [], 'Agree': []}
    for line in stdout.splitlines():
        table, _, rest = line.partition(',')
        if table in rows:
            # terms contain no commas: table,v,col...,(full tuple)
            fields = rest.split(',')
            rows[table].append(fields[:-1])
    return rows


def classify(v, rows):
    intents = [r for r in rows['Intent'] if int(r[0]) == v]
    today = [r for r in rows['TodayUp'] if int(r[0]) == v]
    if any(int(r[0]) == v for r in rows['Agree']):
        kind = 'same'
    elif intents and today:
        kind = 'differ'
    elif intents:
        kind = 'egglog-only'
    elif today:
        kind = 'today-only'
    elif any(int(r[0]) == v for r in rows['TodayExact']):
        kind = 'exact'
    else:
        kind = 'literal'
    return kind, intents, today


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('facts_dir')
    ap.add_argument('--egglog', default=shutil.which('egglog') or os.path.expanduser('~/.cargo/bin/egglog'))
    ap.add_argument('--timeout', type=float, default=20.0)
    ap.add_argument('--csv', help='write one row per division to this file')
    args = ap.parse_args()

    prelude = (HERE / 'rules.egg').read_text() + '\n' + (HERE / 'compare.egg').read_text() + '\n'
    files = sorted(Path(args.facts_dir).glob('*.egg'))
    if not files:
        sys.exit(f'no .egg files in {args.facts_dir}')

    per_node, per_site, timeouts, errors, multi = Counter(), {}, [], [], []
    out_rows, times = [], []
    with tempfile.TemporaryDirectory() as tmp:
        prog = Path(tmp) / 'prog.egg'
        for f in files:
            text = f.read_text()
            meta, roots = parse_header(text)
            prog.write_text(prelude + text + RUN)
            start = time.monotonic()
            try:
                p = subprocess.run([args.egglog, str(prog)], capture_output=True, text=True, timeout=args.timeout)
            except subprocess.TimeoutExpired:
                timeouts.append(f.name)
                continue
            times.append((time.monotonic() - start, f.name))
            if p.returncode != 0:
                errors.append((f.name, (p.stderr or p.stdout).strip().splitlines()[-1:]))
                continue
            rows = parse_rows(p.stdout)
            for v, pos in sorted(roots.items()):
                kind, intents, today = classify(v, rows)
                per_node[kind] += 1
                per_site[(meta.get('source'), meta.get('method'), v)] = kind
                if len(intents) > 1:
                    multi.append((f.name, pos, intents))
                out_rows.append({
                    'file': f.name, 'method': meta.get('method'), 'position': pos, 'root': v, 'kind': kind,
                    'egglog': ' | '.join(f'n={r[1]} d={r[2]}' for r in intents),
                    'today': ' | '.join(f'n={r[1]} d={r[2]}' for r in today),
                })

    print(f'{len(files)} files (call-graph nodes with a division), egglog {args.egglog}')
    if times:
        total = sum(t for t, _ in times)
        slowest = max(times)
        print(f'egglog time: {total:.1f} s total, slowest {slowest[0]:.2f} s ({slowest[1]})')
    print(f'timeouts (> {args.timeout:g} s): {len(timeouts)}   errors: {len(errors)}')
    print()
    print(f'{"kind":<12} {"nodes":>7} {"sites":>7}')
    site_counts = Counter(per_site.values())
    for kind in ('same', 'differ', 'egglog-only', 'today-only', 'literal', 'exact'):
        print(f'{kind:<12} {per_node[kind]:>7} {site_counts[kind]:>7}')
    print(f'{"total":<12} {sum(per_node.values()):>7} {len(per_site):>7}')

    shown = set()
    for r in out_rows:
        key = (r['method'], r['position'], r['root'], r['kind'])
        if r['kind'] in ('differ', 'egglog-only', 'today-only') and key not in shown:
            shown.add(key)
            print(f"\n[{r['kind']}] {r['position']}  {r['method']}  (root {r['root']}, {r['file']})")
            if r['egglog']:
                print(f"  egglog: {r['egglog']}")
            if r['today']:
                print(f"  today:  {r['today']}")
    for name, pos, intents in multi:
        print(f'\n[several readings] {pos} ({name}): ' + ' | '.join(f'n={r[1]} d={r[2]}' for r in intents))
    for name in timeouts:
        print(f'\n[timeout] {name}')
    for name, msg in errors:
        print(f'\n[error] {name}: {msg}')

    if args.csv:
        with open(args.csv, 'w', newline='') as fh:
            w = csv.DictWriter(fh, fieldnames=list(out_rows[0].keys()) if out_rows else ['file'])
            w.writeheader()
            w.writerows(out_rows)


if __name__ == '__main__':
    main()

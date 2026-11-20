#!/usr/bin/env python3
"""Run rules.egg on the facts EgglogExport wrote and compare with today's phase 1.

Usage: run_facts.py FACTS_DIR [--egglog PATH] [--timeout SECONDS] [--csv OUT.csv]

Each facts file holds one call-graph node: a (let ...) per SSA value, a (Root v t)
per candidate site (every division, and every + or * with an indicator among its
operands or a ceiling today), and TodayUp / TodayExact for what phase 1 decides
today. The script joins rules.egg, compare.egg and the facts into one program,
runs the written and match phases of rules.egg until nothing changes (or the
timeout), and classifies each site. The Intent rows are complete after those
phases, so the optional readings phase (the union of each DivUp term into its
site's class) is not run. The classes are:

  same        egglog and today both read it as div(n, d, Up), with equal n, d and condition
  differ      both find a round-up reading, but with different n, d or condition
  egglog-only egglog finds a round-up reading, today does not
  today-only  today finds one, egglog does not (expected for the guarded row,
              whose N != 0 guard is not exported)
  literal     neither does
  exact       division by the constant 1

Sites are counted by root kind (div, add, mul). A site analyzed in several
contexts appears in several files; the summary counts call-graph nodes and also
distinct sites. A site is one source expression: the source file plus the
offsets of the expression's text, which the exporter writes on the ";@root"
line. The SSA value number is not used, because each context has its own IR
and the same number can name different expressions. A site that is classified
differently in different nodes is counted as mixed and listed. Today's ceilings
at phis (the branch row) are not exported and are only counted.

Constants are printed as plain numbers: egglog's (from-string "5") is shown as 5.
"""
import argparse
import csv
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPAN = re.compile(r'^(\d+-\d+|\?)$')
BIGINT = re.compile(r'\((?:from-string|bigint) "?(-?\d+)"?\)')
RUN = """
(run-schedule (saturate (run written)) (saturate (run match)))
(print-function Intent :mode csv)
(print-function TodayUp :mode csv)
(print-function TodayExact :mode csv)
(print-function Agree :mode csv)
"""


def parse_header(text):
    meta, roots, today, uncovered = {}, {}, {}, []
    for line in text.splitlines():
        if line.startswith(';@root '):
            # ;@root v kind File.sol:line first-last   (older facts have no offsets)
            _, v, kind, pos = line.split(' ', 3)
            pos, sep, span = pos.rpartition(' ')
            if not sep or not SPAN.match(span):
                pos, span = (pos + sep + span).strip(), ''
            roots[int(v)] = (kind, pos, span if span != '?' else '')
        elif line.startswith(';@today '):
            _, v, kind = line.split(' ', 2)
            today[int(v)] = kind
        elif line.startswith(';@uncovered '):
            _, v, kind = line.split(' ', 2)
            uncovered.append(kind)
        elif line.startswith(';@') and not line.startswith(';@cond '):
            key, _, value = line[2:].partition(' ')
            meta[key] = value
    return meta, roots, today, uncovered


def show(term):
    """A printed term with its constants as plain numbers."""
    return BIGINT.sub(r'\1', term)


def parse_rows(stdout):
    rows = {'Intent': [], 'TodayUp': [], 'TodayExact': [], 'Agree': []}
    # each line is table,v,col...,(full tuple); a term holding a string is quoted
    for fields in csv.reader(stdout.splitlines()):
        if fields and fields[0] in rows:
            rows[fields[0]].append([show(x) for x in fields[1:-1]])
    return rows


def site_key(meta, v, rootkind, pos, span):
    """One source expression; without offsets (older facts), the value number in its method."""
    if span:
        return (meta.get('source'), pos.rsplit(':', 1)[0], span, rootkind)
    return (meta.get('source'), meta.get('method'), v, rootkind)


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
    uncovered_sites = {}
    out_rows, times = [], []
    with tempfile.TemporaryDirectory() as tmp:
        prog = Path(tmp) / 'prog.egg'
        for f in files:
            text = f.read_text()
            meta, roots, today_kind, uncovered = parse_header(text)
            for i, kind in enumerate(uncovered):
                uncovered_sites[(meta.get('source'), meta.get('method'), i, kind)] = kind
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
            for v, (rootkind, pos, span) in sorted(roots.items()):
                kind, intents, today = classify(v, rows)
                per_node[(rootkind, kind)] += 1
                site = per_site.setdefault(site_key(meta, v, rootkind, pos, span),
                                           {'rootkind': rootkind, 'position': pos, 'kinds': set(), 'todayrows': set()})
                site['kinds'].add(kind)
                if today_kind.get(v):
                    site['todayrows'].add(today_kind[v])
                if len(intents) > 1:
                    multi.append((f.name, pos, intents))
                out_rows.append({
                    'file': f.name, 'source': meta.get('source'), 'method': meta.get('method'), 'position': pos,
                    'span': span, 'root': v,
                    'rootkind': rootkind, 'kind': kind, 'todayrow': today_kind.get(v, ''),
                    'egglog': ' | '.join(f'n={r[1]} d={r[2]} c={r[3]}' for r in intents),
                    'today': ' | '.join(f'n={r[1]} d={r[2]} c={r[3]}' for r in today),
                })

    print(f'{len(files)} files (call-graph nodes with a division), egglog {args.egglog}')
    if times:
        total = sum(t for t, _ in times)
        slowest = max(times)
        print(f'egglog time: {total:.1f} s total, slowest {slowest[0]:.2f} s ({slowest[1]})')
    print(f'timeouts (> {args.timeout:g} s): {len(timeouts)}   errors: {len(errors)}')
    print()
    site_counts = Counter((s['rootkind'], next(iter(s['kinds'])) if len(s['kinds']) == 1 else 'mixed')
                          for s in per_site.values())
    rootkinds = ('div', 'add', 'mul')
    print(f'{"":<12}' + ''.join(f'{rk + " nodes":>11}{rk + " sites":>11}' for rk in rootkinds))
    for kind in ('same', 'differ', 'egglog-only', 'today-only', 'literal', 'exact'):
        print(f'{kind:<12}' + ''.join(f'{per_node[(rk, kind)]:>11}{site_counts[(rk, kind)]:>11}' for rk in rootkinds))
    print(f'{"mixed":<12}' + ''.join(f'{"":>11}{site_counts[(rk, "mixed")]:>11}' for rk in rootkinds))
    print(f'{"total":<12}' + ''.join(
        f'{sum(v for (r, _), v in per_node.items() if r == rk):>11}{sum(v for (r, _), v in site_counts.items() if r == rk):>11}'
        for rk in rootkinds))
    print(f'\ntoday\'s branch-row ceilings (phis, not exported): {len(uncovered_sites)} distinct')
    today_rows = Counter(row for s in per_site.values() for row in s['todayrows'])
    print('today\'s ceilings at exported sites, by row: ' + ', '.join(f'{k} {v}' for k, v in sorted(today_rows.items())))

    for s in per_site.values():
        if len(s['kinds']) > 1:
            print(f"\n[mixed] {s['position']} ({s['rootkind']} root): " + ', '.join(sorted(s['kinds'])))
    shown = set()
    for r in out_rows:
        key = (r['source'], r['position'], r['span'] or (r['method'], r['root']), r['rootkind'], r['kind'])
        if r['kind'] in ('differ', 'egglog-only', 'today-only') and key not in shown:
            shown.add(key)
            row = f", today's row: {r['todayrow']}" if r['todayrow'] else ''
            print(f"\n[{r['kind']}] {r['position']}  {r['method']}  ({r['rootkind']} root {r['root']}{row}, {r['file']})")
            if r['egglog']:
                print(f"  egglog: {r['egglog']}")
            if r['today']:
                print(f"  today:  {r['today']}")
    for name, pos, intents in multi:
        print(f'\n[several readings] {pos[1] if isinstance(pos, tuple) else pos} ({name}): ' + ' | '.join(f'n={r[1]} d={r[2]} c={r[3]}' for r in intents))
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

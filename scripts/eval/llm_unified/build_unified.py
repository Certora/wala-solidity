#!/usr/bin/env python3
"""Build the unified LLM study (user design, 2026-10-14): the rename study's 58 questions (55 name
questions + the tricky 3) and the 160 microbenchmark questions, each in a named and a renamed
version, asked by one direct API call with the code pasted into the prompt.

Code shown per question:
  - real code bases (the 58): the question's file and every file it imports, directly or
    indirectly (its import closure), from the rename study's workspace (the .sol files RoundAbout
    compiled, minus certora/ harnesses);
  - fixtures (the 160): every .sol file of the fixture.
Both versions have comments removed (line breaks kept), so the names are the only difference.
Renamed (user choice "per set"):
  - real code: every identifier containing a direction or rounding word (the rename study's
    rule, extended with round/exact/bias/ceil/floor/trunc and the enum members Expand, Zero),
    one map per code base;
  - fixtures: build_micro's map, which also renames every contract, function and file.
Reference verdict: RoundAbout on the frozen jar 2ee3816 (sha 887d2626...), identical on the named
and renamed code where both were run.

Run from eval-artifacts/:
  python3 ../scripts/eval/llm_unified/build_unified.py <fixture-tree> llm-unified
<fixture-tree> holds test/data/ as committed at 2ee3816 (git archive 2ee3816 test/data).
"""
import json
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'llm_micro'))
sys.path.insert(0, os.path.join(HERE, '..', 'llm_agent'))
from build_micro import EXACT_WORDS, EXTRA, anonymize as anonymize_all, locate, strip_comments  # noqa: E402
from build_workspaces import DIRWORD, TOKEN  # noqa: E402
from score_micro import EXCLUDED  # noqa: E402

TRICKY = {  # RoundAbout at 2ee3816, named and renamed: llm-agent/roundabout-tricky-2ee3816/README.md
    'witness-TestBalancerStablePool4f189ea1-BaseGeneralPool-onSwap-L32': 'Indeterminate',
    'witness-TestCozyEuler-SafetyModuleInspector-convertToReserveAssetAmount-L38': 'Indeterminate',
    'exploit-CompoundV2Actual-CToken-exchangeRateStoredInternal-L293': 'Down',
}
VOCAB = {'Neither': 'Exact', 'Down': 'Down', 'Up': 'Up', 'Inconsistent': 'Indeterminate'}
IDENT = re.compile(r'[A-Za-z_$][A-Za-z0-9_$]*')
IMPORT = re.compile(r'\bimport\s+(?:[^;"\']*?\bfrom\s+)?["\']([^"\']+)["\']')


def slug(root):
    return root.replace('test/data/', '').replace('/', '__')


def rename_words(texts):
    """Comments removed; identifiers with a direction/rounding word renamed, one map."""
    used = {m.group(0) for t in texts.values() for m in TOKEN.finditer(strip_comments(t))}
    renames, n = {}, 0

    def sub(m):
        nonlocal n
        tok = m.group(0)
        if tok.startswith(('"', "'")):
            return tok
        if tok.startswith('//'):
            return ''
        if tok.startswith('/*'):
            return '\n' * tok.count('\n')
        if DIRWORD.search(tok) or EXTRA.search(tok) or tok in EXACT_WORDS:
            if tok not in renames:
                while True:
                    n += 1
                    if f'id{n}' not in used:
                        break
                renames[tok] = f'id{n}'
            return renames[tok]
        return tok
    return {p: TOKEN.sub(sub, t) for p, t in texts.items()}, renames


def resolve(files, src, path):
    """The workspace file an import in `src` names; None if outside the workspace."""
    if path.startswith('.'):
        cand = os.path.normpath(os.path.join(os.path.dirname(src), path))
        return cand if cand in files else None
    if path in files:
        return path
    parts = path.split('/')
    for k in range(len(parts) - 1):  # remapped imports: drop leading segments until one file matches
        tail = '/'.join(parts[k:])
        hits = [f for f in files if f == tail or f.endswith('/' + tail)]
        if len(hits) == 1:
            return hits[0]
        if len(hits) > 1:
            raise SystemExit(f'{src}: import {path} is ambiguous: {hits[:4]}')
    return None


def closure(files, start):
    seen, todo, unresolved = set(), [start], []
    while todo:
        f = todo.pop()
        if f in seen:
            continue
        seen.add(f)
        for m in IMPORT.finditer(strip_comments(files[f])):
            r = resolve(files, f, m.group(1))
            if r is None:
                unresolved.append((f, m.group(1)))
            else:
                todo.append(r)
    return sorted(seen), unresolved


def sub_names(s, renames):
    return IDENT.sub(lambda m: renames.get(m.group(0), m.group(0)), s)


def write(out, files):
    for p, t in files.items():
        os.makedirs(os.path.dirname(os.path.join(out, p)) or out, exist_ok=True)
        with open(os.path.join(out, p), 'w') as fh:
            fh.write(t)


def main():
    tree, out = sys.argv[1], sys.argv[2]
    ws_out = os.path.join(out, 'workspaces')
    shutil.rmtree(ws_out, ignore_errors=True)
    items = []

    # --- the 58 questions on real code bases
    old = {i['id']: i for i in json.load(open('llm-agent/items.json'))}
    names = json.load(open('llm-agent/pure-selection.json'))['ids']
    ra = {q['id']: q for q in json.load(open('llm-agent/roundabout-rename-2ee3816/results-all.json'))['questions']}
    for root in sorted({old[q]['root'] for q in names + list(TRICKY)}):
        base = os.path.join('llm-agent', 'workspaces', slug(root), 'named')
        files = {os.path.relpath(os.path.join(d, f), base): open(os.path.join(d, f), errors='replace').read()
                 for d, _, fs in os.walk(base) for f in fs if f.endswith('.sol')}
        named = {p: strip_comments(t) for p, t in files.items()}
        renamed, renames = rename_words(files)
        for p in files:
            if not (files[p].count('\n') == named[p].count('\n') == renamed[p].count('\n')):
                raise SystemExit(f'{root}/{p}: line count changed')
            if DIRWORD.search(os.path.basename(p)[:-4]) or EXTRA.search(os.path.basename(p)[:-4]):
                raise SystemExit(f'{root}/{p}: file name carries a direction word')
        cb = slug(root)
        write(os.path.join(ws_out, cb, 'named'), named)
        write(os.path.join(ws_out, cb, 'renamed'), renamed)
        json.dump(dict(renames=renames), open(os.path.join(ws_out, cb, 'rename-map.json'), 'w'), indent=1)
        for qid in [q for q in names + list(TRICKY) if old[q]['root'] == root]:
            q = old[qid]
            fl, unresolved = closure(files, q['file'])
            if qid in TRICKY:
                ref = TRICKY[qid]
            else:
                r = ra[qid]
                if not (r['named'] == r['renamed'] == [r['label']]):
                    raise SystemExit(f'{qid}: RoundAbout named/renamed/label differ: {r}')
                ref = r['label']
            items.append(dict(
                id=qid, set='tricky' if qid in TRICKY else 'name', codebase=cb, component=None, n_returns=1,
                reference=ref, files=fl, unresolved_imports=unresolved,
                named=dict(contract=q['contract'], signature=q['signature'], deployment=None),
                renamed=dict(contract=sub_names(q['contract'], renames),
                             signature=sub_names(q['signature'], renames), deployment=None)))

    # --- the 160 microbenchmark questions
    micro = [i for i in json.load(open('llm-micro/items.json')) if i['id'] not in EXCLUDED]
    defined_in = {(l['fixture'], l['contract'], l['function']): l['defined_in']
                  for l in json.load(open(os.path.join(HERE, '..', 'llm_micro', 'labels.json')))['labels']
                  if 'defined_in' in l}
    mra = json.load(open('llm-micro/roundabout-2ee3816/verdicts.json'))['verdicts']
    for fx in sorted({i['fixture'] for i in micro}):
        base = os.path.join(tree, 'test', 'data', fx)
        files = {f: open(os.path.join(base, f)).read() for f in sorted(os.listdir(base)) if f.endswith('.sol')}
        renamed, renames, file_renames = anonymize_all(files)
        named = {p: strip_comments(t) for p, t in files.items()}
        cb = 'fixture__' + fx
        write(os.path.join(ws_out, cb, 'named'), named)
        write(os.path.join(ws_out, cb, 'renamed'), renamed)
        json.dump(dict(renames=renames, files=file_renames), open(os.path.join(ws_out, cb, 'rename-map.json'), 'w'), indent=1)
        for i in (x for x in micro if x['fixture'] == fx):
            named_dep = {k: sub_names(v, {b: a for a, b in renames.items()})
                         for k, v in i['anon']['deployment'].items()} if i['anon']['deployment'] else None
            items.append(dict(
                id=i['id'], set='micro', codebase=cb, component=i['component'], n_returns=i['n_returns'],
                reference=mra[i['id']]['verdict'], files=sorted(files), unresolved_imports=[],
                named=dict(contract=i['contract'], signature=i['signature'], deployment=named_dep,
                           defined_in=defined_in.get((fx, i['contract'], i['function']), i['contract'])),
                renamed=dict(contract=i['anon']['contract'], signature=i['anon']['signature'],
                             deployment=i['anon']['deployment'],
                             defined_in=sub_names(defined_in.get((fx, i['contract'], i['function']),
                                                                 i['contract']), renames)),
                renamed_files={p: file_renames[p] for p in files}))

    # the prompt names a function by name only, so its contract must define exactly one function of
    # that name in the code shown (locate raises otherwise); checked in both versions
    for i in items:
        for cond in ('named', 'renamed'):
            d = os.path.join(ws_out, i['codebase'], cond)
            shown = [i['renamed_files'][f] if cond == 'renamed' and 'renamed_files' in i else f for f in i['files']]
            texts = {f: open(os.path.join(d, f)).read() for f in shown}
            c = i[cond]
            try:
                locate(texts, c.get('defined_in', c['contract']), c['signature'].split('(')[0])
            except SystemExit as e:
                raise SystemExit(f"{i['id']} ({cond}): {e}")
    json.dump(items, open(os.path.join(out, 'items.json'), 'w'), indent=1)
    by = {}
    for i in items:
        by[i['set']] = by.get(i['set'], 0) + 1
    unres = sum(len(i['unresolved_imports']) for i in items)
    print(f"{len(items)} questions: {by}; codebases {len({i['codebase'] for i in items})}; "
          f"unresolved imports {unres}")


if __name__ == '__main__':
    main()

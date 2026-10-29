#!/usr/bin/env python3
"""Build the microbenchmark questions and the code the agent sees, one workspace per fixture.

The questions are the functions the public JUnit suite asserts on (labels.json, written from
the code before any model run). The fixtures were written as regression tests, so their
comments state the expected answers and many names describe them (biasDiv, lookupRounded,
sharedOnly, CeilingIdioms.sol). The agent therefore sees only an anonymized copy:

  - comments removed, line breaks kept (the line a question names is the same as in the
    original, which is where RoundAbout reports it);
  - every contract, library, interface and function renamed to a neutral id (C1, f1, ...),
    with one map per fixture so calls across files stay consistent;
  - every other identifier that carries a direction or rounding word renamed too (the
    rename study's rule, extended with Rounded, Exact and Bias);
  - every file renamed after its first contract, with import paths rewritten to match.

Run from eval-artifacts/:
  python3 ../scripts/eval/llm_micro/build_micro.py <fixture-tree> llm-micro
<fixture-tree> holds test/data/<Fixture>/ as committed at the frozen commit (git archive).
Writes llm-micro/items.json and llm-micro/workspaces/<Fixture>/{anon/,anon-map.json}.
"""
import json
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'llm_agent'))
from build_workspaces import TOKEN, DIRWORD  # noqa: E402  the rename study's tokenizer and rule

EXTRA = re.compile(r'(?i)round|exact|bias|ceil|floor|trunc')
# rounding-mode enum members that name a direction without a direction word (OpenZeppelin's
# Rounding.Expand, v4's Rounding.Zero); matched exactly, since a substring rule would hit iszero
EXACT_WORDS = {'Expand', 'Zero'}
DECL = re.compile(r'\b(contract|library|interface)\s+([A-Za-z_$][A-Za-z0-9_$]*)')
FUNC = re.compile(r'\bfunction\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*\(')
LOCATIONS = {'memory', 'calldata', 'storage'}


def strip_comments(text):
    """Comments removed, every line break kept, strings untouched."""
    def sub(m):
        tok = m.group(0)
        if tok.startswith('//'):
            return ''
        if tok.startswith('/*'):
            return '\n' * tok.count('\n')
        return tok
    return TOKEN.sub(sub, text)


def matching(text, i, open_ch, close_ch):
    """Index just past the bracket that closes the one at text[i]."""
    depth = 0
    for j in range(i, len(text)):
        if text[j] == open_ch:
            depth += 1
        elif text[j] == close_ch:
            depth -= 1
            if depth == 0:
                return j + 1
    raise ValueError('unbalanced')


def split_top(s):
    out, depth, cur = [], 0, ''
    for ch in s:
        if ch in '([':
            depth += 1
        elif ch in ')]':
            depth -= 1
        if ch == ',' and depth == 0:
            out.append(cur)
            cur = ''
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    return [p.strip() for p in out]


def param_type(p):
    words = [w for w in p.split() if w not in LOCATIONS]
    return words[0]


def param_name(p):
    words = [w for w in p.split() if w not in LOCATIONS]
    return words[1] if len(words) > 1 else None


def locate(files, contract, function):
    """(file, line, signature, return parameters) of `function` declared in `contract`."""
    hits = []
    for path, text in files.items():
        clean = strip_comments(text)
        for m in DECL.finditer(clean):
            if m.group(2) != contract:
                continue
            start = clean.index('{', m.end())
            body = clean[start:matching(clean, start, '{', '}')]
            for f in FUNC.finditer(body):
                if f.group(1) != function:
                    continue
                at = start + f.start()
                line = clean.count('\n', 0, at) + 1
                p0 = start + f.end() - 1
                p1 = matching(clean, p0, '(', ')')
                params = split_top(clean[p0 + 1:p1 - 1])
                head = clean[p1:clean.index('{', p1) if '{' in clean[p1:] else len(clean)]
                rets = []
                r = re.search(r'\breturns\s*\(', head)
                if r:
                    q0 = p1 + r.end() - 1
                    rets = split_top(clean[q0 + 1:matching(clean, q0, '(', ')') - 1])
                sig = f"{function}({','.join(param_type(p) for p in params)})"
                hits.append((path, line, sig, rets))
    if len(hits) != 1:
        raise SystemExit(f'{contract}.{function}: {len(hits)} definitions found')
    return hits[0]


def anonymize(files):
    """files: {path: text}. Returns ({new path: text}, renames, file renames)."""
    used, declared = set(), []
    for text in files.values():
        clean = strip_comments(text)
        used.update(m.group(0) for m in TOKEN.finditer(clean) if m.group(0)[0].isalpha())
        declared += [m.group(2) for m in DECL.finditer(clean)]
    funcs = sorted({m.group(1) for t in files.values() for m in FUNC.finditer(strip_comments(t))})
    renames, n = {}, {'C': 0, 'f': 0, 'v': 0}

    def fresh(prefix):
        while True:
            n[prefix] += 1
            cand = f'{prefix}{n[prefix]}'
            if cand not in used:
                return cand
    for c in declared:
        renames.setdefault(c, fresh('C'))
    for f in funcs:
        renames.setdefault(f, fresh('f'))

    def sub(m):
        tok = m.group(0)
        if tok.startswith(('"', "'")):
            return tok
        if tok.startswith('//'):
            return ''
        if tok.startswith('/*'):
            return '\n' * tok.count('\n')
        if tok not in renames and (DIRWORD.search(tok) or EXTRA.search(tok) or tok in EXACT_WORDS):
            renames[tok] = fresh('v')
        return renames.get(tok, tok)
    out = {p: TOKEN.sub(sub, t) for p, t in files.items()}
    file_renames = {}
    for p, t in files.items():
        first = DECL.search(strip_comments(t))
        file_renames[p] = (renames[first.group(2)] if first else f'F{len(file_renames) + 1}') + '.sol'
    for p in list(out):
        for old, new in file_renames.items():
            out[p] = out[p].replace(f'"./{old}"', f'"./{new}"').replace(f"'./{old}'", f"'./{new}'")
    return {file_renames[p]: t for p, t in out.items()}, renames, file_renames


def main():
    tree, out = sys.argv[1], sys.argv[2]
    labels = json.load(open(os.path.join(HERE, 'labels.json')))['labels']
    items = []
    for fixture in sorted({l['fixture'] for l in labels}):
        base = os.path.join(tree, 'test', 'data', fixture)
        names = sorted(f for f in os.listdir(base) if f.endswith('.sol'))
        files = {f: open(os.path.join(base, f)).read() for f in names}
        anon, renames, file_renames = anonymize(files)
        for p, t in anon.items():
            if t.count('\n') != files[{v: k for k, v in file_renames.items()}[p]].count('\n'):
                raise SystemExit(f'{fixture}/{p}: anonymization changed the line count')
        d = os.path.join(out, 'workspaces', fixture)
        shutil.rmtree(d, ignore_errors=True)
        os.makedirs(os.path.join(d, 'anon'))
        for p, t in anon.items():
            open(os.path.join(d, 'anon', p), 'w').write(t)
        json.dump(dict(renames=renames, files=file_renames), open(os.path.join(d, 'anon-map.json'), 'w'), indent=1)

        def rn(s):
            return re.sub(r'[A-Za-z_$][A-Za-z0-9_$]*', lambda m: renames.get(m.group(0), m.group(0)), s)
        for l in (x for x in labels if x['fixture'] == fixture):
            path, line, sig, rets = locate(files, l.get('defined_in', l['contract']), l['function'])
            comp = l.get('component')
            if comp is not None and len(rets) < 2:
                raise SystemExit(f"{fixture} {l['function']}: component asked of a single return")
            if comp is None and len(rets) != 1:
                raise SystemExit(f"{fixture} {l['function']}: {len(rets)} return values, no component")
            qid = f"{fixture}.{l['contract']}.{l['function']}" + (f'#{comp}' if comp is not None else '')
            items.append(dict(
                id=qid, fixture=fixture, contract=l['contract'], function=l['function'],
                signature=sig, file=path, line=line, component=comp,
                n_returns=len(rets),
                return_name=rn(param_name(rets[comp]) or '') or None if comp is not None else None,
                anon=dict(contract=rn(l['contract']), signature=rn(sig), file=file_renames[path],
                          deployment={k: rn(v) for k, v in l['deployment'].items()} if 'deployment' in l else None),
                label=l['label'], accept=l.get('accept', [l['label']]), ambiguity=l.get('ambiguity')))
    ids = [i['id'] for i in items]
    if len(ids) != len(set(ids)):
        raise SystemExit('duplicate question ids')
    json.dump(items, open(os.path.join(out, 'items.json'), 'w'), indent=1)
    print(f'{len(items)} questions, {len({(i["fixture"], i["contract"], i["function"]) for i in items})} functions, '
          f'{len({i["fixture"] for i in items})} fixtures, '
          f'{sum(1 for i in items if not i["ambiguity"])} unambiguous')


if __name__ == '__main__':
    main()

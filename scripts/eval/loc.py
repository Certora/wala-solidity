#!/usr/bin/env python3
"""Lines of code per corpus configuration: compiled and analyzed.

A line counts if it holds Solidity code after comments are removed (non-blank,
non-comment lines). Files under a certora/ directory - the verification harnesses
and helpers the audits added - are left out: they are not protocol code.

  compiled  every source file in the configuration's compilation, as listed in its
            AST (libraries and interfaces included)
  analyzed  the lines inside the functions RoundAbout analyzed: every node of its
            call graph, by the source span (methodPosition) the result JSON records

Configurations can share files (copies of the same library in different
directories, or one source tree used by two configurations), so the per-row
numbers overlap. The DISTINCT row counts each file once across the corpus, by
content: identical copies count once, and an analyzed line counts once per file
content.

Fails if an AST path does not resolve or an analyzed file is outside the
compilation.

Usage: loc.py <suite-env-root> <json-dir> <out.tsv>
  suite-env-root  the environment the results were produced in (make_env.sh)
  json-dir        the result JSONs, <TestName>_1.json (e.g. eval-artifacts/private-frozen)
"""
import bz2
import collections
import hashlib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from corpus import CORPUS

SPAN = re.compile(r'^(.*):\[(\d+),\d+-(\d+),\d+\]$')


def code_lines(src):
    """For each line of src, whether code remains on it once comments are removed.
    String literals are honoured, so '//' inside a string is not a comment."""
    out, has, i, n = [], False, 0, len(src)
    while i < n:
        c = src[i]
        if c == '\n':
            out.append(has)
            has = False
            i += 1
        elif src.startswith('//', i):
            while i < n and src[i] != '\n':
                i += 1
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            for _ in range(src.count('\n', i, j)):
                out.append(has)
                has = False
            i = j
        elif c in '"\'':
            has = True
            i += 1
            while i < n and src[i] != c and src[i] != '\n':
                i += 2 if src[i] == '\\' else 1
            i += 1
        else:
            if not c.isspace():
                has = True
            i += 1
    out.append(has)
    return out


def harness(path):
    return '/certora/' in path.replace(os.sep, '/')


def resolve(root, d, key):
    """An AST source key names its file relative to the run directory, which may be
    the suite root or the configuration's directory or one of its parents."""
    key = key.removeprefix('./')
    cands, base = [os.path.join(root, key)], d
    while base:
        cands.append(os.path.join(root, base, key))
        base = os.path.dirname(base)
    for p in cands:
        if os.path.isfile(p):
            return os.path.normpath(p)
    return None


def compute(root, jdir):
    """Returns dict(rows=[(test, files, compiled, analyzed)], total=(...), distinct=(...))."""
    lines, digest = {}, {}

    def info(p):
        if p not in lines:
            raw = open(p, 'rb').read()
            digest[p] = hashlib.sha1(raw).hexdigest()
            lines[p] = code_lines(raw.decode('utf-8', errors='replace'))
        return lines[p]

    rows, bad = [], []
    distinct_compiled, distinct_analyzed = {}, set()
    for test, d, _, ast in CORPUS:
        a = os.path.join(root, d, ast)
        doc = json.load(bz2.open(a)) if os.path.isfile(a) else json.load(open(a.removesuffix('.bz2')))
        files = set()
        for v in doc.values():
            for key in (v if isinstance(v, dict) else {}):
                p = resolve(root, d, key)
                if p is None:
                    bad.append(f"{test}: unresolved AST path {key}")
                elif not harness(p):
                    files.add(p)
        compiled = 0
        for p in files:
            k = sum(info(p))
            compiled += k
            distinct_compiled[digest[p]] = k
        spans = collections.defaultdict(set)
        for g in json.load(open(os.path.join(jdir, f'{test}_1.json'))).get('graphs', []):
            for node in g.get('nodes', {}).values():
                m = SPAN.match((node.get('metadata') or {}).get('methodPosition') or '')
                if m:
                    p = os.path.normpath(os.path.join(root, 'test/data', m.group(1).split('/test/data/')[-1]))
                    if not harness(p):
                        spans[p].update(range(int(m.group(2)), int(m.group(3)) + 1))
        analyzed = 0
        for p, ls in spans.items():
            if p not in files:
                bad.append(f"{test}: analyzed file outside the compilation: {p}")
                continue
            fl = info(p)
            hit = [ln for ln in ls if 0 < ln <= len(fl) and fl[ln - 1]]
            analyzed += len(hit)
            distinct_analyzed.update((digest[p], ln) for ln in hit)
        rows.append((test, len(files), compiled, analyzed))
    if bad:
        raise SystemExit("\n".join(bad[:20]))
    total = (sum(r[1] for r in rows), sum(r[2] for r in rows), sum(r[3] for r in rows))
    distinct = (len(distinct_compiled), sum(distinct_compiled.values()), len(distinct_analyzed))
    return dict(rows=rows, total=total, distinct=distinct)


def main():
    root, jdir, out = sys.argv[1], sys.argv[2], sys.argv[3]
    r = compute(root, jdir)
    with open(out, 'w') as f:
        f.write("configuration\tfiles\tcompiled\tanalyzed\n")
        for row in r['rows']:
            f.write("\t".join(map(str, row)) + "\n")
        f.write("TOTAL\t" + "\t".join(map(str, r['total'])) + "\n")
        f.write("DISTINCT\t" + "\t".join(map(str, r['distinct'])) + "\n")
    for t, nf, c, a in sorted(r['rows'], key=lambda x: -x[2]):
        print(f"{t:36s} files {nf:4d}  compiled {c:7,d}  analyzed {a:6,d} ({100 * a / c:3.0f}%)")
    for name, (nf, c, a) in (('TOTAL', r['total']), ('DISTINCT', r['distinct'])):
        print(f"{name:36s} files {nf:4d}  compiled {c:7,d}  analyzed {a:6,d} ({100 * a / c:3.0f}%)")
    print(f"wrote {out}")


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Build the read-only code workspaces the agent sees, two per code base.

  workspaces/<slug>/named/  the .sol files the tool compiled for that code base, verbatim
  workspaces/<slug>/anon/   the same files with comments removed and every identifier that
                            carries a direction word renamed, using ONE map for the whole
                            code base so calls across files stay consistent

The file set is read from the code base's own ASTs (the sources solc compiled), so the
agent sees exactly the code the tool analyzed. Files under certora/ (verification
harnesses, which can discuss rounding) are left out. Anonymization keeps string literals
intact, so import paths still resolve, and keeps every line break, so the line number a
question names is the same in both conditions. Rename maps are saved for audit.

Run from eval-artifacts/:
  python3 ../scripts/eval/llm_agent/build_workspaces.py llm-agent/items.json \
      <suite-env-root> llm-agent/workspaces
<suite-env-root> holds the corpus code bases (test/data/...); case-study code bases are
read from the wala-solidity checkout.
"""
import bz2
import json
import os
import re
import shutil
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..'))

# A token stream good enough to anonymize safely: strings, comments, identifiers.
TOKEN = re.compile(r'"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'|//[^\n]*|/\*.*?\*/'
                   r'|\b[A-Za-z_$][A-Za-z0-9_$]*\b', re.S)
DIRWORD = re.compile(
    r'(?:(?<=[a-z0-9_])|^)(Up|Down|Ceil|Ceiling|Floor|Round|Rounding|Trunc\w*)(?=[A-Z_0-9]|$)'
    r'|(?:^|(?<=_))(up|down|ceil|floor|round\w*|trunc\w*)(?=_|$)')


def slug(root):
    return root.replace('test/data/', '').replace('/', '__')


def sources(root_dir):
    """Relative paths of every source in this code base's ASTs."""
    out = set()
    for dirpath, _, files in os.walk(root_dir):
        for f in files:
            if f in ('.asts.json', '.asts.json.bz2') and os.path.basename(dirpath) == 'ast':
                p = os.path.join(dirpath, f)
                d = json.load(bz2.open(p, 'rt') if f.endswith('.bz2') else open(p))
                for unit in d.values():
                    out.update(unit.keys())
    rel = set()
    for s in out:
        s = s.replace('/./', '/')
        if s.startswith('./test/data/') or s.startswith('test/data/'):
            s = s.split(os.path.relpath(root_dir, start=os.path.dirname(root_dir.rstrip('/'))), 1)[-1]
            s = s.split('/', 1)[1] if s.startswith('/') else s
        rel.add(s.lstrip('./') if s.startswith('./') else s)
    return sorted(rel)


def anonymize(texts):
    """texts: {path: source}. Returns ({path: anonymized}, {old: new})."""
    used = set()
    for t in texts.values():
        used.update(m.group(0) for m in TOKEN.finditer(t) if m.group(0)[0].isalpha())
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
        if DIRWORD.search(tok):
            if tok not in renames:
                while True:
                    n += 1
                    cand = f'id{n}'
                    if cand not in used:
                        break
                renames[tok] = cand
            return renames[tok]
        return tok

    return {p: TOKEN.sub(sub, t) for p, t in texts.items()}, renames


def main():
    items_path, env_root, out = sys.argv[1], sys.argv[2], sys.argv[3]
    items = json.load(open(items_path))
    roots = sorted({i['root'] for i in items})
    summary = {}
    for root in roots:
        base = os.path.join(REPO if '_repros' in root else env_root, root)
        files = [f for f in sources(base) if not f.startswith('certora/')]
        missing = [f for f in files if not os.path.isfile(os.path.join(base, f))]
        if missing:
            raise SystemExit(f"{root}: {len(missing)} compiled sources not on disk, e.g. {missing[:3]}")
        needed = {i['file'] for i in items if i['root'] == root}
        if not needed <= set(files):
            raise SystemExit(f"{root}: question files not in the compiled set: {needed - set(files)}")
        texts = {f: open(os.path.join(base, f), errors='replace').read() for f in files}
        anon, renames = anonymize(texts)
        for cond, tx in (('named', texts), ('anon', anon)):
            d = os.path.join(out, slug(root), cond)
            shutil.rmtree(d, ignore_errors=True)
            for f, t in tx.items():
                os.makedirs(os.path.dirname(os.path.join(d, f)), exist_ok=True)
                with open(os.path.join(d, f), 'w') as fh:
                    fh.write(t)
        for f in files:
            if texts[f].count('\n') != anon[f].count('\n'):
                raise SystemExit(f"{root}/{f}: anonymization changed the line count")
        leaked = [f for f in files if DIRWORD.search(os.path.basename(f).rsplit('.', 1)[0])]
        json.dump(dict(renames=renames, filenames_with_direction_words=leaked),
                  open(os.path.join(out, slug(root), 'anon-map.json'), 'w'), indent=1)
        summary[root] = (len(files), len(renames), leaked)
    for root, (nf, nr, leaked) in summary.items():
        print(f"{slug(root):55s} {nf:4d} files  {nr:4d} renames"
              + (f"  FILE NAMES WITH DIRECTION WORDS: {leaked}" if leaked else ""))


if __name__ == '__main__':
    main()

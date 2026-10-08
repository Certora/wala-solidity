#!/usr/bin/env python3
"""Select the rename study's questions: the name questions whose function is `pure`.

A pure function's result depends only on its parameters, so a direction word in its name
describes the whole computation and is a sound label. Name questions on `view` (or
state-changing) functions are left out: their name certifies only the last rounding step.

The function is located in source by the question's root, file and line (the line of the
function's header, as the tool reports it). The header runs to the first `{` or `;`;
`pure` is matched as a whole token after comments are stripped.

Counts are reported two ways, and anything citing them must say which:
  per question       one per (root, file, contract, signature) - what the agent is asked
  per function body  distinct function source text (header + body, comments removed,
                     whitespace collapsed): the same library copied into several code
                     bases is one body

Usage: select_pure.py <items.json> <source root> <out.json>
  <source root> holds the corpus code bases (a private-suite environment's priv/).
"""
import collections
import json
import os
import re
import sys

COMMENT = re.compile(r'//[^\n]*|/\*.*?\*/', re.S)
MUTABILITY = ('pure', 'view', 'payable')


def function_text(lines, start):
    """Source text of the function whose header is on line `start` (1-based), header to
    matching close brace; for a body-less declaration, header to `;`."""
    text = COMMENT.sub(' ', '\n'.join(lines[start - 1:]))
    depth, seen_brace = 0, False
    for i, c in enumerate(text):
        if c == ';' and not seen_brace:
            return text[:i + 1]
        if c == '{':
            depth, seen_brace = depth + 1, True
        elif c == '}':
            depth -= 1
            if seen_brace and depth == 0:
                return text[:i + 1]
    raise ValueError("unbalanced braces")


def main():
    items_path, src, outp = sys.argv[1:4]
    items = json.load(open(items_path))
    names = [i for i in items if i['group'] == 'name']
    rows = []
    for it in names:
        path = os.path.join(src, it['root'], it['file'])
        lines = open(path, encoding='utf-8').read().split('\n')
        fn = function_text(lines, it['line'])
        header = fn.split('{', 1)[0]
        if not re.search(r'\bfunction\s+' + re.escape(it['signature'].split('(')[0]) + r'\s*\(', header):
            raise SystemExit(f"{it['id']}: line {it['line']} of {path} is not the header of "
                             f"{it['signature']}")
        tokens = set(re.findall(r'\w+', header))
        mut = next((m for m in MUTABILITY if m in tokens), 'nonpayable')
        rows.append(dict(item=it, mutability=mut, body=' '.join(fn.split())))

    pure = [r for r in rows if r['mutability'] == 'pure']
    bodies = collections.OrderedDict()
    for r in pure:
        bodies.setdefault(r['body'], []).append(r['item']['id'])

    print(f"name questions: {len(rows)}  by mutability: "
          f"{dict(collections.Counter(r['mutability'] for r in rows))}")
    print(f"selected (pure): {len(pure)} per question, {len(bodies)} per function body")
    agrees = {r['item']['id']: r['item']['tool'] == r['item']['label'] for r in pure}
    print(f"RoundAbout agrees with the name: {sum(agrees.values())}/{len(pure)} per question, "
          f"{sum(all(agrees[i] for i in ids) for ids in bodies.values())}/{len(bodies)} "
          f"per function body")
    for r in rows:
        if r['mutability'] != 'pure':
            print(f"  excluded ({r['mutability']}): {r['item']['id']}  tool={r['item']['tool']} "
                  f"label={r['item']['label']}")
    json.dump(dict(ids=[r['item']['id'] for r in pure],
                   bodies=[dict(questions=ids) for ids in bodies.values()]),
              open(outp, 'w'), indent=1)
    print(f"wrote {outp}")


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Build the prompt files for the LLM comparison, two conditions per item.

  named/<id>.txt   the source file verbatim
  anon/<id>.txt    comments stripped; every identifier containing a direction word
                   renamed to an opaque name (consistently within the file); the
                   per-item rename map saved for audit

The prompt is identical for every item except the function name and the source.
The whole source FILE is included - no token budget, by design.

Usage: build_prompts.py <manifest.json> <out-dir>
"""
import json
import os
import re
import sys

PROMPT = """You are auditing Solidity code for rounding behavior.

Below is the complete source of a Solidity file. Consider the function `{fn}`.
Its INTENDED value is the exact real-number result of the computation it
implements: divisions exact, no truncation, no rounding.

Classify the rounding direction of `{fn}`'s result relative to its intended
value, over all valid inputs:

- Exact: always equal to the intended value
- Down: always less than or equal to the intended value
- Up: always greater than or equal to the intended value
- Indeterminate: can be on either side of the intended value, depending on inputs

Answer with exactly one of: Exact, Down, Up, Indeterminate - alone on the first
line. Then give at most three sentences of justification.

```solidity
{src}
```
"""

# camel-case segments and words that leak a direction; conservative on purpose
DIRWORD = re.compile(
    r'(?:(?<=[a-z0-9_])|^)(Up|Down|Ceil|Ceiling|Floor|Round|Rounding|Trunc\w*)(?=[A-Z_0-9(]|$)'
    r'|(?:^|(?<=_))(up|down|ceil|floor|round\w*|trunc\w*)(?=_|$)')
IDENT = re.compile(r'\b[A-Za-z_$][A-Za-z0-9_$]*\b')
COMMENT = re.compile(r'//[^\n]*|/\*.*?\*/', re.S)


def anonymize(src, fn):
    src = COMMENT.sub('', src)
    renames = {}

    def visit(m):
        name = m.group(0)
        if name in renames:
            return renames[name]
        if DIRWORD.search(name):
            renames[name] = f"f{len(renames) + 1}"
            return renames[name]
        return name

    out = IDENT.sub(visit, src)
    return out, renames


def main():
    manifest, outdir = sys.argv[1], sys.argv[2]
    items = json.load(open(manifest))
    os.makedirs(os.path.join(outdir, 'named'), exist_ok=True)
    os.makedirs(os.path.join(outdir, 'anon'), exist_ok=True)
    maps = {}
    skipped = []
    for it in items:
        try:
            src = open(it['file']).read()
        except OSError as e:
            skipped.append((it['id'], str(e)))
            continue
        with open(os.path.join(outdir, 'named', it['id'] + '.txt'), 'w') as f:
            f.write(PROMPT.format(fn=it['fn'], src=src))
        asrc, ren = anonymize(src, it['fn'])
        afn = ren.get(it['fn'], it['fn'])
        with open(os.path.join(outdir, 'anon', it['id'] + '.txt'), 'w') as f:
            f.write(PROMPT.format(fn=afn, src=asrc))
        maps[it['id']] = {'fn_anon': afn, 'renames': ren}
    json.dump(maps, open(os.path.join(outdir, 'anon-maps.json'), 'w'), indent=1)
    print(f"wrote {2 * (len(items) - len(skipped))} prompts under {outdir}")
    if skipped:
        print("SKIPPED (missing source):")
        for sid, err in skipped:
            print("  ", sid, err)
        raise SystemExit(1)


if __name__ == '__main__':
    main()

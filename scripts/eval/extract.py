#!/usr/bin/env python3
"""Extract per-function results from a directory of analysis JSONs.

Produces the three sorted extracts the gates and checkers consume:
  <tag>-returns.txt    every node's (test | method | context | return)
  <tag>-roots.txt      entry-point nodes only (the deterministic contract)
  <tag>-roundings.txt  every recorded rounding site

Usage: python3 extract.py <json-dir> <out-prefix>
JSON file names must be <TestName>_<k>.json; the manifest's prefixes are checked
against what is found, and a missing corpus test is an error.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from corpus import PROT

jdir, prefix = sys.argv[1], sys.argv[2]


def ctx(md):
    return "[" + ",".join(p.get("rounding", "") for p in md.get("parameters", [])) + "]"


def ret(md):
    r = md.get("return")
    if isinstance(r, dict):
        return "{" + ", ".join(f"{k}={v}" for k, v in sorted(r.items())) + "}"
    return str(r)


returns, roots, roundings = [], [], []
seen = set()
for f in sorted(os.listdir(jdir)):
    if not f.endswith(".json"):
        continue
    test = f[:-5]
    seen.add(test.rsplit("_", 1)[0])
    doc = json.load(open(os.path.join(jdir, f)))
    for g in doc.get("graphs", []):
        nodes = g.get("nodes", {})
        for key, node in nodes.items():
            md = node.get("metadata") or {}
            if md.get("method") is None:
                continue
            row = f"{test} | {md['method']} | {ctx(md)} | return={ret(md)}"
            returns.append(row)
            if key == "0":
                roots.append(row)
            for pos, site in (md.get("roundings") or {}).items():
                roundings.append(f"{test} | {md['method']} | {ctx(md)} | {pos} | "
                                 f"{site.get('rounding')} | {site.get('source', '')}")

missing = sorted(PROT - seen)
if missing:
    for t in missing:
        print(f"MISSING JSONs for corpus test {t}", file=sys.stderr)
    raise SystemExit(1)

for name, rows in (("returns", returns), ("roots", roots), ("roundings", roundings)):
    out = f"{prefix}-{name}.txt"
    with open(out, "w") as fh:
        fh.write("\n".join(sorted(rows)) + "\n")
    print(f"wrote {out}: {len(rows)} rows")
print(f"corpus coverage: {len(PROT & seen)}/{len(PROT)} (+{len(seen - PROT)} synthetic)")

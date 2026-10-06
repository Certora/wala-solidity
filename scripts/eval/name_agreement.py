#!/usr/bin/env python3
"""Developer-name agreement (eval 'Correctness'): functions whose names assert a
direction, checked against the reported direction in all-exact contexts.
Run from eval-artifacts/ against private-<tag>-returns.txt."""
import os, re, sys, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from protocols import PROT
UP   = re.compile(r'(Up|Ceil)$|^ceilDiv$|^divUp$|^mulDivUp$|^rayDivUp$|^mulUp$')
DOWN = re.compile(r'(Down|Floor|Truncate|Truncated)$|^divDown$|^mulDivDown$|^rayDivDown$|^mulDown$')
# names where Up/Down/Ceiling/Floor is not a rounding claim
EXCL = re.compile(r'RoundsUp$|WithFloor$|Lookup$|^getDebtCeiling$')
src = sys.argv[1] if len(sys.argv)>1 else 'private-noeither-returns.txt'
cases = set()
for r in set(open(src).read().splitlines()):
    t = r.split('|')[0].strip().rsplit('_',1)[0]
    if t not in PROT: continue
    m = re.search(r'<Code body of function (\w+)>', r)
    if not m: continue
    fn = m.group(1).lstrip('_')
    if EXCL.search(fn): continue
    if re.search(r'Down|Up|Inconsistent', r.split('|')[2]): continue
    ret = r.split('return=')[1]
    if ret.startswith('{'): continue
    e = 'Up' if UP.search(fn) else ('Down' if DOWN.search(fn) else None)
    if e: cases.add((fn, e, ret, t))
n=len(cases); ok=sum(1 for f,e,g,t in cases if e==g)
print(f"results {n}  agree {ok} ({100*ok/n:.1f}%)  functions {len({f for f,_,_,_ in cases})}")
for f,e,g,t in sorted(cases):
    if e!=g: print(f"  DISAGREE {f}: named {e} -> {g}  [{t}]")

#!/usr/bin/env python3
"""Distribution restricted to functions containing >=1 recognized rounding site
(eval 'Functions that can round'). Run from eval-artifacts/."""
import os, re, sys, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from protocols import PROT
tag = sys.argv[1] if len(sys.argv)>1 else 'noeither'
divfns = set()
for r in set(open(f'private-{tag}-roundings.txt').read().splitlines()):
    p=[x.strip() for x in r.split('|')]
    if len(p)<2: continue
    t=p[0].rsplit('_',1)[0]
    if t in PROT: divfns.add((t,p[1]))
tot = collections.Counter()
for r in set(open(f'private-{tag}-returns.txt').read().splitlines()):
    p=[x.strip() for x in r.split('|')]
    if len(p)<4 or 'return=' not in r: continue
    t=p[0].rsplit('_',1)[0]
    if t not in PROT or (t,p[1]) not in divfns: continue
    ret=r.split('return=')[1]
    if ret.startswith('{'):
        for d in re.findall(r'>=(\w+)', ret): tot[d]+=1
    else: tot[ret]+=1
N=sum(tot.values()); d=tot['Neither']+tot['Down']+tot['Up']
print(f"functions {len(divfns)}  results {N}")
for k in ["Neither","Down","Up","Inconsistent"]: print(f"  {k} {tot[k]} ({100*tot[k]/N:.1f}%)")
print(f"definite {d} ({100*d/N:.1f}%)")

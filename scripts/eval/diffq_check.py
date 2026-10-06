#!/usr/bin/env python3
"""Join the differential harness's TSVs (diffq/*.tsv) with the root verdict extract and
check every verdict against the observed integer-vs-rational relation.

Verdict semantics checked per sampled function:
  Up      -> no sample may fall below the rational run
  Down    -> no sample may fall above
  Neither -> every sample equal
  Inconsistent -> nothing required; both-sided observations CONFIRM the refusal.

Run from eval-artifacts/:  python3 scripts/diffq_check.py [roots-extract] [diffq-dir]
"""
import collections
import os
import re
import sys

ROOTS = sys.argv[1] if len(sys.argv) > 1 else 'private-scratch-roots.txt'
DIR = sys.argv[2] if len(sys.argv) > 2 else 'diffq'

# tag prefix (dir-derived) -> extract test prefix
TAGMAP = {
    'AaveV3_PoolInstance-PoolInstance_sanity': 'TestAaveV3PoolInstanceSanity',
    'AaveV3_PoolInstance-PoolInstance_builtin_assertions': 'TestAaveV3PoolInstanceBuiltin',
    'AaveV4_HubValidState': 'TestAaveV4HubValidState',
    'AaveV4_Liquidation': 'TestAaveV4Liquidation',
    'BalancerV2_stablePool_4f189ea1': 'TestBalancerStablePool4f189ea1',
    'BalancerV2_stablePool_PaminaNov25': 'TestBalancerStablePoolPaminaNov25',
    'Cork_0Auxiliary': 'TestCorkAuxiliary',
    'CozyEuler_TrancheRaiseStrategy': 'TestCozyEuler',
    'EigenLayer_EigenPodManagerRules': 'TestEigenLayer',
    'ENS_ETHRegistrar': 'TestEnsEthRegistrar',
    'EulerEarn_Solvency': 'TestEulerEarn',
    'Gho_GsmOptimality': 'TestGhoGsmOptimality',
    'InfiniFi_MintController': 'TestInfiniFiMintController',
    'Mezzanine_IssuanceVault': 'TestMezzanine',
    'MorphoV2_Midnight': 'TestMorphoMidnight',
    'MorphoV2_SharePrice': 'TestMorphoSharePrice',
    'RoycoDawn_AccoutantSanity': 'TestRoycoAccountant',
    'SaturnDollar_USDatBacking': 'TestSaturnDollar',
    'Tokemak': 'TestTokemak',  # refined below by conf name if needed
    'VedaBoring_AccountantWithRateProviders': 'TestVedaBoring',
}

def test_of(tag):
    for k in sorted(TAGMAP, key=len, reverse=True):
        if tag.startswith(k):
            return TAGMAP[k]
    return None

# verdicts per (test, function-name): multiset of scalar returns in all-exact contexts
verdicts = collections.defaultdict(set)
for r in set(open(ROOTS).read().splitlines()):
    parts = [p.strip() for p in r.split('|')]
    if len(parts) < 4 or 'return=' not in r:
        continue
    test = parts[0].rsplit('_', 1)[0]
    m = re.search(r'<Code body of function ([^>]+)>', parts[1])
    if not m:
        continue
    ret = r.split('return=', 1)[1]
    if ret.startswith('{'):
        continue
    verdicts[(test, m.group(1))].add(ret)

ok = viol = nov = indet2 = indet1 = skipped = 0
viols = []
indet_confirmed = []
for f in sorted(os.listdir(DIR)):
    if not f.endswith('.tsv'):
        continue
    tag = f[:-4]
    test = test_of(tag)
    for line in open(os.path.join(DIR, f)).read().splitlines()[1:]:
        cols = line.split('\t')
        if len(cols) < 9:
            continue
        name_m = re.search(r'<Code body of function ([^>]+)>', cols[0])
        if not name_m or test is None:
            continue
        fn = name_m.group(1)
        above, below, equal, amb, disc = map(int, cols[3:8])
        evaluated = above + below + equal
        if evaluated == 0:
            skipped += 1
            continue
        vs = verdicts.get((test, fn))
        if not vs:
            nov += 1
            continue

        def compatible(v):
            if v == 'Up':
                return below == 0
            if v == 'Down':
                return above == 0
            if v == 'Neither':
                return above == 0 and below == 0
            return True  # Inconsistent: no claim

        if any(compatible(v) for v in vs):
            ok += 1
            if vs == {'Inconsistent'}:
                if above > 0 and below > 0:
                    indet2 += 1
                    indet_confirmed.append((test, fn, above, below, equal))
                else:
                    indet1 += 1
        else:
            viol += 1
            viols.append((test, fn, sorted(vs), above, below, equal, amb, disc))

print(f"functions checked: {ok + viol}  OK: {ok}  VIOLATIONS: {viol}")
print(f"  Indet functions: two-sided confirmed {indet2}, one-sided {indet1}")
print(f"  rows with no verdict match: {nov}, fully discarded: {skipped}")
if viols:
    print("\nVIOLATIONS (test, function, verdicts, above, below, equal, ambiguous, discarded):")
    for v in viols:
        print("  ", v)
if indet_confirmed:
    print("\nIndet confirmed two-sided (first 15):")
    for v in indet_confirmed[:15]:
        print("  ", v)

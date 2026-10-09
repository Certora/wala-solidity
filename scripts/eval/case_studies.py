#!/usr/bin/env python3
"""Check every tool verdict the case-studies subsection quotes, against the outputs.

Each check names the sentence it supports. Inputs (run from eval-artifacts/):
  case-studies/cs-{CompoundV2,Raft}Actual.json                - tool runs on shipped code
  private-<tag>/TestBalancerStablePool*_*.json                - the corpus fixtures

Produce the case-study JSONs with:
  java -ea -jar <jar> test/data/_repros/<B>/run.conf case-studies/cs-<B>.json --combined <ast>

Usage: case_studies.py <tag>
"""
import glob
import json
import re
import sys


def _roots(paths):
    out = {}
    for p in paths:
        for g in json.load(open(p)).get('graphs', []):
            md = (g.get('nodes', {}).get('0') or {}).get('metadata') or {}
            m = re.search(r'<Code body of function ([^>]+)>', md.get('method') or '')
            if m:
                out.setdefault(m.group(1), []).append(md)
    return out


def _ret(md):
    r = str(md.get('return'))
    if r.startswith('{'):
        vals = set(re.findall(r'>=(\w+)', r))
        return vals.pop() if len(vals) == 1 else 'mixed-tuple'
    return r


def _site(md, line, source_prefix):
    return [s['rounding'] for pos, s in (md.get('roundings') or {}).items()
            if pos.startswith(f'[{line},') and s.get('source', '').startswith(source_prefix)]


def _sites(roots, fn, line, source_prefix):
    """The roundings at one source site, over every root graph of fn."""
    return {r for md in roots.get(fn, []) for r in _site(md, line, source_prefix)}


def compute(tag):
    """Returns a list of (ok, sentence) pairs."""
    compound = _roots(['case-studies/cs-CompoundV2Actual.json'])
    raft = _roots(['case-studies/cs-RaftActual.json'])
    bal4f = _roots(glob.glob(f'private-{tag}/TestBalancerStablePool4f189ea1_*.json'))
    balnov = _roots(glob.glob(f'private-{tag}/TestBalancerStablePoolPaminaNov25_*.json'))

    def all_ret(roots, fn, want):
        mds = roots.get(fn, [])
        return bool(mds) and all(_ret(md) == want for md in mds)

    checks = [
        (all_ret(compound, 'exchangeRateStoredInternal', 'Down'),
         "Sonne/Compound: the exchange rate is Down"),
        (_sites(compound, 'redeemFresh', 496, 'mul_ScalarTruncate(exchangeRate, redeemTokensIn)') == {'Down'},
         "Sonne/Compound: the redeemed amount (exchange rate times tokens) is Down"),
        (_sites(compound, 'redeemFresh', 503, 'div_(redeemAmountIn, exchangeRate)') == {'Inconsistent'},
         "Sonne/Compound: the burn division is Indet at its source line"),
        (_sites(compound, 'redeemFresh', 532, 'totalSupply - redeemTokens') == {'Inconsistent'}
         and _sites(compound, 'redeemFresh', 533, 'accountTokens[redeemer] - redeemTokens') == {'Inconsistent'},
         "Sonne/Compound: burned tokens are Indet where subtracted from totalSupply and the balance"),
        (_sites(raft, 'mint', 2474, 'amount.divUp(storedIndex)') == {'Up'}
         and _sites(raft, 'burn', 2478, 'amount.divUp(storedIndex)') == {'Up'}
         and _sites(raft, 'setIndex', 2483, 'backingAmount.divUp(supply)') == {'Up'},
         "Raft: mint, burn and setIndex all divide rounding Up"),
        (_sites(balnov, '_swapGivenOut', 74, '_upscale(swapRequest.amount') == {'Down'}
         and _sites(balnov, '_swapGivenOut', 76, '_onSwapGivenOut(') == {'Inconsistent'}
         and _sites(balnov, '_swapGivenOut', 79, '_downscaleUp(amountIn') == {'Inconsistent'},
         "Balancer: in _swapGivenOut the upscaled amount is Down, amountIn Indet before and after _downscaleUp"),
        (all_ret(bal4f, '_calcInGivenOut', 'Inconsistent')
         and all_ret(bal4f, '_onSwapGivenOut', 'Inconsistent'),
         "Balancer: _calcInGivenOut and _onSwapGivenOut are Indet"),
        (all_ret(balnov, '_getRate', 'Inconsistent') and all_ret(bal4f, 'getRate', 'Inconsistent'),
         "Balancer: the pool-token rate is Indet"),
        (all_ret(bal4f, '_calculateInvariant', 'Inconsistent')
         and all_ret(bal4f, '_getTokenBalanceGivenInvariantAndAllOtherBalances', 'Inconsistent'),
         "Balancer: both iterative solvers are Indet"),
    ]
    return checks


def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else 'scratch'
    checks = compute(tag)
    for ok, sentence in checks:
        print(f"{'ok  ' if ok else 'FAIL'} {sentence}")
    bad = sum(1 for ok, _ in checks if not ok)
    print(f"\n{len(checks) - bad}/{len(checks)} case-study claims hold")
    if bad:
        raise SystemExit(1)


if __name__ == '__main__':
    main()

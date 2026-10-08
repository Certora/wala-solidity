#!/usr/bin/env python3
"""Build the question set for the agent-based LLM baseline.

Every question names one function exactly - protocol root, file, contract, signature,
line - and carries a ground-truth label fixed here, before any model is queried, plus
the tool's own verdict for the same function (from the canonical run). Labels come from
four sources independent of the tool:

  name     the developer-declared direction in the function's name (all-exact context)
  review   a security-review ruling
  witness  Indeterminate proved by the differential harness (inputs erring both ways)
  exploit  adjudicated by a historical exploit's post-mortem

Questions are keyed by (root, file, contract, signature), so identical code reached from
two configurations is asked once, and same-named functions in different contracts stay
distinct. Functions defined under certora/ (verification harnesses) are excluded, because
their files are not shown to the agent.

Run from eval-artifacts/:
  python3 ../scripts/eval/llm_agent/build_items.py <tag> llm-agent/items.json
"""
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..'))
from corpus import CORPUS  # noqa: E402

UP = re.compile(r'(Up|Ceil)$|^ceilDiv$|^divUp$|^mulDivUp$|^rayDivUp$|^mulUp$')
DOWN = re.compile(r'(Down|Floor|Truncate|Truncated)$|^divDown$|^mulDivDown$|^rayDivDown$|^mulDown$')
EXCL = re.compile(r'RoundsUp$|WithFloor$|Lookup$|^getDebtCeiling$')
TOOL = {'Neither': 'Exact', 'Down': 'Down', 'Up': 'Up', 'Inconsistent': 'Indeterminate'}
LABEL = re.compile(r'^graph of < solidity, Lcontract (\S+)\.(\S+) \(([^)]*)\)')
POS = re.compile(r'^(.*):\[(\d+),\d+-\d+,\d+\]$')

# case-study configurations: result JSON -> code root
CASES = {
    'case-studies/cs-CompoundV2Actual.json': 'test/data/_repros/CompoundV2Actual',
    'case-studies/cs-KyberActual.json': 'test/data/_repros/KyberActual',
}


def verdict(md):
    r = str(md.get('return'))
    if r.startswith('{'):
        vals = set(re.findall(r'>=(\w+)', r))
        r = vals.pop() if len(vals) == 1 else 'tuple'
    return TOOL.get(r, r)


def functions(json_paths, root, test):
    """All-exact roots of these result JSONs, keyed by (root, file, contract, signature)."""
    out = {}
    for p in json_paths:
        for g in json.load(open(p)).get('graphs', []):
            md = (g.get('nodes', {}).get('0') or {}).get('metadata') or {}
            lab, pos = LABEL.match(g.get('label', '')), POS.match(md.get('methodPosition') or '')
            if not lab or not pos or md.get('method') is None:
                continue
            if not all(q.get('rounding') == 'Neither' for q in md.get('parameters', [])):
                continue
            path = pos.group(1).replace('/./', '/')
            marker = '/' + root.strip('./') + '/'
            if marker not in path:
                continue
            rel = path.split(marker, 1)[1]
            contract, name = lab.group(1), lab.group(2)
            key = (root, rel, contract, f"{name}({lab.group(3)})")
            e = out.setdefault(key, dict(root=root, file=rel, contract=contract, name=name,
                                         signature=key[3], line=int(pos.group(2)),
                                         test=test, verdicts=set()))
            e['verdicts'].add(verdict(md))
    return out


def main():
    tag, outp = sys.argv[1], sys.argv[2]
    fns = {}
    for test, root, _, _ in CORPUS:
        for k, v in functions(sorted(glob.glob(f'private-{tag}/{test}_*.json')), root, test).items():
            fns.setdefault(k, v)  # first configuration wins; same code is asked once
    for jf, root in CASES.items():
        fns.update(functions([jf], root, os.path.basename(jf)[3:-5]))

    def item(group, label, e, note):
        if e['file'].startswith('certora/'):
            return None
        if len(e['verdicts']) != 1:
            raise SystemExit(f"conflicting tool verdicts for {e['signature']}: {e['verdicts']}")
        return dict(id=f"{group}-{e['test']}-{e['contract']}-{e['name']}-L{e['line']}",
                    group=group, label=label, root=e['root'], file=e['file'],
                    contract=e['contract'], signature=e['signature'], line=e['line'],
                    tool=next(iter(e['verdicts'])), note=note)

    items, skipped = [], []

    # name: the developer-declared direction, exactly as in name_agreement.py
    for e in fns.values():
        base = e['name'].lstrip('_')
        if EXCL.search(base) or e['test'] not in {t for t, _, _, _ in CORPUS}:
            continue
        lab = 'Up' if UP.search(base) else ('Down' if DOWN.search(base) else None)
        if lab and 'tuple' not in e['verdicts']:
            it = item('name', lab, e, 'direction declared by the function name')
            (items if it else skipped).append(it or e['signature'])

    def find(test, name, root_hint=None):
        hits = [e for e in fns.values() if e['test'] == test and e['name'] == name]
        if len(hits) != 1:
            raise SystemExit(f"expected one {test}.{name}, found {len(hits)}")
        return hits[0]

    items.append(item('review', 'Indeterminate', find('TestMorphoMidnight', 'liquidate'),
                      'security review: incentive factor branches on a rounded comparison'))

    # witness: the two functions the canonical differential run (diffq/, commit c9ea6e5)
    # proved two-sided. Named here rather than recomputed, so this builder does not depend
    # on the differential checker, which is being reworked.
    for test, contract, name, counts in [
        ('TestBalancerStablePool4f189ea1', 'BaseGeneralPool', 'onSwap', '1640 above / 13 below'),
        ('TestCozyEuler', 'SafetyModuleInspector', 'convertToReserveAssetAmount', '2 above / 857 below'),
    ]:
        hits = [e for e in fns.values()
                if e['test'] == test and e['contract'] == contract and e['name'] == name]
        if len(hits) != 1:
            raise SystemExit(f"witness {test}.{contract}.{name} matched {len(hits)} functions")
        items.append(item('witness', 'Indeterminate', hits[0],
                          f'differential harness found inputs erring both ways ({counts})'))

    for test, name, label, note in [
        ('CompoundV2Actual', 'exchangeRateStoredInternal', 'Down',
         'Compound/Sonne: truncating exchange rate behind the under-burned redeem'),
        ('KyberActual', 'computeSwapStep', 'Indeterminate',
         'Kyber: deltaL floored where the design needs a ceiling; outputs uncontrolled'),
        ('KyberActual', 'calcReachAmount', 'Indeterminate', 'Kyber: same incident'),
        # Radiant's rayDiv is left out: half-up rounding is a disclosed limitation, and the
        # tool reads it literally as Down.
    ]:
        items.append(item('exploit', label, find(test, name), note))

    items = [i for i in items if i]
    ids = [i['id'] for i in items]
    assert len(ids) == len(set(ids)), "duplicate item ids"
    json.dump(items, open(outp, 'w'), indent=1)
    groups = {}
    for i in items:
        groups[i['group']] = groups.get(i['group'], 0) + 1
    print(f"wrote {outp}: {len(items)} questions {groups}; "
          f"{len(skipped)} skipped (defined under certora/)")


if __name__ == '__main__':
    main()

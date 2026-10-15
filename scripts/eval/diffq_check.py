#!/usr/bin/env python3
"""Check the differential harness's TSVs (diffq/*.tsv) against the analysis's verdicts.

Each TSV row is one call-graph node and carries the analysis's own all-exact verdict for
that node (DifferentialQ asks RoundingAnalysis.analyzeForNode, the call the JSON report
makes), so no join by name is needed. The verdict is still cross-checked against the
published result JSONs - by signature for entry points, by source position for other
nodes - and any disagreement is an error: we check the reported results, not a re-run.

Verdict semantics checked per row, on the stratified samples and on the witness search:
  Up      -> no sample below the rational run
  Down    -> no sample above
  Neither -> every sample equal
  Inconsistent -> nothing required; the harness looks for witnesses on both sides.

Entry-point counts reproduce the earlier report (stratified samples only); internal nodes
(--all-nodes) and the witness search (--search=N) are reported separately. Counts are
given as ROWS (one per call-graph node) and as distinct FUNCTIONS (test, signature) or
DEFINITIONS (test, source position); prose must name which one it cites.

Run from eval-artifacts/:  python3 ../scripts/eval/diffq_check.py [roots] [diffq-dir]
"""
import collections
import csv
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from corpus import CORPUS, tag as tag_of

TAGMAP = {tag_of(t): t for t, _, _, _ in CORPUS}

LABEL = re.compile(r'^graph of < solidity, (.*?), do\([^)]*\)[^>]*> \((.*)\)$')
SCALAR = {'Up', 'Down', 'Neither', 'Inconsistent'}


def _relpos(p):
    """A methodPosition without the machine-specific prefix of the suite environment."""
    return p.split('/test/data/', 1)[-1]


def _published(json_dir):
    """Scalar all-exact verdicts from the result JSONs: by (test, signature) for entry
    points, and by (test, source position) for every node."""
    roots, nodes = collections.defaultdict(set), collections.defaultdict(set)
    for f in sorted(os.listdir(json_dir)):
        if not f.endswith('.json'):
            continue
        test = f[:-5].rsplit('_', 1)[0]
        for g in json.load(open(os.path.join(json_dir, f))).get('graphs', []):
            m = LABEL.match(g.get('label', ''))
            for key, node in g.get('nodes', {}).items():
                md = node.get('metadata') or {}
                if md.get('method') is None:
                    continue
                if not all(p.get('rounding') == 'Neither' for p in md.get('parameters', [])):
                    continue
                ret = str(md.get('return'))
                if ret not in SCALAR:
                    continue
                nodes[(test, _relpos(md.get('methodPosition', '')))].add(ret)
                if key == '0' and m:
                    roots[(test, m.group(1))].add(ret)
    return roots, nodes


def _rows(d):
    for f in sorted(os.listdir(d)):
        if not f.endswith('.tsv'):
            continue
        with open(os.path.join(d, f), newline='') as fh:
            rd = csv.DictReader(fh, delimiter='\t', quoting=csv.QUOTE_NONE)
            if 'verdict' not in (rd.fieldnames or []):
                raise SystemExit(f"{f}: old-format TSV without verdicts; rerun the harness")
            for r in rd:
                yield TAGMAP[f[:-4]], r


def _ints(r, *cols):
    return [int(r[c]) for c in cols]


def compute(roots, d):
    seen_tags = {f[:-4] for f in os.listdir(d) if f.endswith('.tsv')}
    missing, extra = sorted(set(TAGMAP) - seen_tags), sorted(seen_tags - set(TAGMAP))
    if missing or extra:
        for t in missing:
            print(f"MISSING RESULTS for {TAGMAP[t]} ({t}.tsv)")
        for t in extra:
            print(f"UNEXPECTED RESULTS FILE {t}.tsv")
        raise SystemExit(1)
    pub_roots, pub_nodes = _published(roots)

    c = collections.Counter()
    fns = collections.defaultdict(set)
    viols, confirmed, search_viols, mismatches = [], [], [], []
    samples = set()
    indet = {}  # (test, position) -> best evidence class, over its Inconsistent rows
    seen_dir = collections.defaultdict(set)  # 'entry'/'internal' -> definitions whose direction showed
    rank = {'untested': 0, 'no divergence seen': 1, 'one-sided': 2, 'proven': 3}

    for test, r in _rows(d):
        entry = r['entry'] == '1'
        v = r['verdict']
        sig = r['method'].split('.<Code body of function')[0]
        pos = (test, _relpos(r['position']))
        above, below, equal = _ints(r, 'above', 'below', 'equal')
        s_above, s_below, s_equal = _ints(r, 'searchAbove', 'searchBelow', 'searchEqual')
        samples.add(int(r['samples']))

        # the harness's verdict must be the published one. Entry points match exactly; an
        # internal node is matched by source position only, because the report does not
        # record WALA's calling context (e.g. one specialization per enum constant), so an
        # internal node whose verdict is not among its position's published ones is
        # excluded and counted rather than fatal.
        if v in SCALAR:
            pub = pub_roots.get((test, sig)) if entry else pub_nodes.get(pos)
            if pub and v not in pub:
                if entry:
                    mismatches.append((test, sig[:80], v, sorted(pub)))
                else:
                    c['internal_context_mismatch'] += 1
                    continue
            if not entry and not pub:
                c['internal_unpublished'] += 1

        # violations, stratified phase and search
        def forbidden(a, b):
            return {'Up': b > 0, 'Down': a > 0, 'Neither': a > 0 or b > 0}.get(v, False)

        if forbidden(s_above, s_below):
            search_viols.append((test, sig, v, s_above, s_below,
                                 r['witnessAbove'] if s_above else r['witnessBelow']))
        if v in ('Up', 'Down') and ((v == 'Up' and above + s_above) or (v == 'Down' and below + s_below)):
            seen_dir['entry' if entry else 'internal'].add(pos)
        if v == 'Inconsistent':
            a, b = above + s_above, below + s_below
            evaluated = above + below + equal + s_above + s_below + s_equal > 0
            k = ('proven' if a and b else 'one-sided' if a or b
                 else 'no divergence seen' if evaluated else 'untested')
            if rank[k] >= rank.get(indet.get(pos), -1):
                indet[pos] = k
            c[f"indet_rows_{k}"] += 1

        if not entry:
            c['internal_rows'] += 1
            if above + below + equal:
                c['internal_evaluated'] += 1
                if forbidden(above, below):
                    viols.append((test, sig, v, above, below, equal, r['ambiguous'], r['discarded']))
            continue

        # entry points: the counts the paper reports (stratified samples only)
        c['rows'] += 1
        if above + below + equal == 0:
            c['void' if 'returns0' in r['reasons'] else 'out_of_scope'] += 1
            continue
        c['evaluated'] += 1
        key = (test, sig)
        if v not in SCALAR:
            c['no_verdict'] += 1
            continue
        fns['checked'].add(key)
        if forbidden(above, below):
            c['viol'] += 1
            viols.append((test, sig, v, above, below, equal, r['ambiguous'], r['discarded']))
            continue
        c['ok'] += 1
        if v == 'Inconsistent':
            side = 'indet2' if above > 0 and below > 0 else 'indet1'
            c[side] += 1
            fns[side].add(key)
            if side == 'indet2':
                confirmed.append((test, sig[:80], above, below, equal))

    if mismatches:
        raise SystemExit("harness verdicts disagree with the published results:\n  "
                         + "\n  ".join(map(str, mismatches[:20])))
    return dict(configs=len(seen_tags), counts=c,
                functions={k: len(s) for k, s in fns.items()},
                function_sets=dict(fns), violations=viols, confirmed=confirmed,
                samples_per_function=sorted(samples), search_violations=search_viols,
                indet=collections.Counter(indet.values()),
                directional={k: len(s) for k, s in seen_dir.items()},
                directional_all=len(set().union(*seen_dir.values())) if seen_dir else 0)


def main():
    roots = sys.argv[1] if len(sys.argv) > 1 else 'private-scratch'
    d = sys.argv[2] if len(sys.argv) > 2 else 'diffq'
    r = compute(roots, d)
    c, fn = r['counts'], r['functions']
    print(f"coverage: {r['configs']}/{len(TAGMAP)} corpus configurations")
    print("entry points (stratified samples, as reported):")
    print(f"  checked: {c['ok'] + c['viol']}  OK: {c['ok']}  VIOLATIONS: {c['viol']}")
    print(f"  Indet: two-sided confirmed {fn.get('indet2', 0)} functions ({c['indet2']} rows), "
          f"one-sided {fn.get('indet1', 0)} functions ({c['indet1']} rows)")
    print(f"  rows with no single verdict: {c['no_verdict']}, "
          f"fully discarded: {c['void'] + c['out_of_scope']}")
    print(f"  distinct functions checked: {fn.get('checked', 0)}; rows: total {c['rows']}, "
          f"void {c['void']}, evaluated {c['evaluated']}, out of scope {c['out_of_scope']}; "
          f"samples per function {r['samples_per_function']}")
    if c['internal_rows']:
        print(f"internal nodes: rows {c['internal_rows']}, evaluated {c['internal_evaluated']}, "
              f"verdict context absent from the report {c['internal_unpublished']}, "
              f"excluded as context-ambiguous {c['internal_context_mismatch']}")
    print(f"definitions whose Up/Down direction was exhibited: {r['directional_all']} "
          f"(entry {r['directional'].get('entry', 0)}, internal {r['directional'].get('internal', 0)})")
    print(f"Indet definitions by evidence: {dict(r['indet'])}")
    print(f"search violations: {len(r['search_violations'])}")
    if r['violations']:
        print("\nVIOLATIONS (test, function, verdict, above, below, equal, ambiguous, discarded):")
        for v in r['violations']:
            print("  ", v)
    if r['search_violations']:
        print("\nSEARCH VIOLATIONS (test, function, verdict, above, below, witness):")
        for v in r['search_violations']:
            print("  ", v)
    if r['confirmed']:
        print("\nIndet confirmed two-sided at entry points (first 15):")
        for v in r['confirmed'][:15]:
            print("  ", v)


if __name__ == '__main__':
    main()

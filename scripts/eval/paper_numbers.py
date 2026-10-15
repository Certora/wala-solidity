#!/usr/bin/env python3
"""Generate every number the evaluation section reports.

Writes, under <paper>/generated/:
  eval-numbers.tex   \\newcommand macros for every number in the prose
  corpus-table.tex   the rows of the corpus table, plus its total row
  loc-table.tex      the rows of the lines-of-code table

The paper uses only these macros and fragments, so no reported number is typed by
hand and every number traces to one function below. Run from eval-artifacts/:

  python3 ../scripts/eval/paper_numbers.py <tag> ../paper/roundabout_latex

where <tag> names the canonical extracts (private-<tag>-returns.txt, ...) and the
result JSON directory private-<tag>/. All other inputs are fixed paths in this
directory: loc/<tag>-loc.tsv (scripts/eval/loc.py), runs/<tag>-mvn-test*.log (one per repeated run; times are
per-configuration means), ablation/<tag>-disable{Positions,Intent}-*.txt (extracts of the
same jar run with one flag on), case-studies/ (and case-studies/ablation-<tag>/).

The LLM comparison (the rename study) is not generated here; its table is written by
hand from llm_agent/score_rename.py's output.
"""
import glob
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ablation  # noqa: E402
import case_studies  # noqa: E402
import corpus_table  # noqa: E402
import div_subset  # noqa: E402
import indet_split  # noqa: E402
import name_agreement  # noqa: E402
import timings  # noqa: E402
from corpus import EXCLUDED, PROT, PROTOCOL  # noqa: E402

# Display names for the corpus table, in the paper's wording.
DISPLAY = {
    'TestBalancerStablePool4f189ea1': 'Balancer ComposableStablePool',
    'TestEigenLayer': 'EigenLayer',
    'TestCorkAuxiliary': 'Cork Auxiliary',
    'TestEulerEarn': 'Euler Earn',
    'TestInfiniFiMintController': 'InfiniFi MintController',
    'TestMezzanine': 'Mezzanine IssuanceVault',
    'TestRoycoAccountant': 'Royco Accountant',
    'TestBalancerStablePoolPaminaNov25': 'Balancer StablePool',
    'TestAaveV4Liquidation': 'Aave v4 Liquidation',
    'TestAaveV3PoolInstanceSanity': 'Aave v3 PoolInstance',
    'TestTokemakVault': 'Tokemak Vault',
    'TestSaturnDollar': 'Saturn Dollar',
    'TestTokemakStrategy': 'Tokemak Strategy',
    'TestAaveV4HubValidState': 'Aave v4 Hub',
    'TestGhoGsmOptimality': 'GHO GSM',
    'TestEnsEthRegistrar': 'ENS EthRegistrar',
    'TestCozyEuler': 'Cozy Safety Module',
    'TestMorphoMidnight': 'Morpho Midnight',
    'TestVedaBoring': 'Veda BoringVault',
    'TestMorphoSharePrice': 'Morpho Share Price',
}
assert set(DISPLAY) == PROT, "DISPLAY must name exactly the corpus configurations"

WORDS = {1: 'one', 2: 'two', 3: 'three', 4: 'four', 5: 'five', 6: 'six', 7: 'seven',
         8: 'eight', 9: 'nine', 10: 'ten'}


def _loc(path):
    """loc.py's output: {test or TOTAL or DISTINCT: (files, compiled, analyzed)}."""
    rows = {}
    for line in open(path).read().splitlines()[1:]:
        t, *v = line.split('\t')
        rows[t] = tuple(int(x) for x in v)
    missing = sorted(PROT - set(rows))
    if missing or 'DISTINCT' not in rows:
        raise SystemExit(f"{path}: no lines-of-code rows for {missing or ['DISTINCT']}")
    return rows


def n(x):
    """An integer in the paper's style: 4{,}301."""
    return f"{x:,}".replace(',', '{,}')


def pct(x):
    return f"{x:.1f}\\%"


def main():
    tag, paper = sys.argv[1], sys.argv[2]
    out = os.path.join(paper, 'generated')
    os.makedirs(out, exist_ok=True)
    m = {}  # macro name -> value

    # corpus and setup
    ct = corpus_table.compute(f'private-{tag}-returns.txt')
    synth = {r.split('|')[0].strip().rsplit('_', 1)[0]
             for r in open(f'private-{tag}-returns.txt').read().splitlines()} - PROT - set(EXCLUDED)
    multi = sorted(p for p in set(PROTOCOL.values()) if list(PROTOCOL.values()).count(p) > 1)
    m.update(evProtocolsMulti=WORDS.get(len(multi), str(len(multi))))
    m.update(evConfigurations=n(len(PROT)), evProtocols=n(len(set(PROTOCOL.values()))), evSynthetic=n(len(synth)), evResults=n(ct['results']),
             evPctDefinite=pct(ct['pct']['definite']), evPctExact=pct(ct['pct']['exact']),
             evPctDown=pct(ct['pct']['down']), evPctUp=pct(ct['pct']['up']),
             evPctIndet=pct(ct['pct']['indet']), evNonExact=n(ct['non_exact']),
             evExact=n(ct['exact']), evDown=n(ct['down']), evUp=n(ct['up']))

    # lines of code (each file counted once across the corpus)
    loc = _loc(f'loc/{tag}-loc.tsv')
    _, lc, la = loc['DISTINCT']
    m.update(evLocCompiled=n(lc), evLocAnalyzed=n(la))

    # performance
    tm = timings.compute(sorted(glob.glob(f'runs/{tag}-mvn-test*.log')))  # mean over repeated runs
    m.update(evTimeRuns=WORDS.get(tm['runs'], str(tm['runs'])), evTimeMedian=f"{tm['median']:.1f}", evTimeSlowest=f"{tm['slowest_s']:.0f}",
             evTimeSlowestName=DISPLAY[tm['slowest']],
             evTimeTotalMin=f"{tm['total'] / 60:.1f}")

    # functions that can round
    ds = div_subset.compute(tag)
    c, N = ds['counts'], ds['results']
    m.update(evDivFunctions=n(ds['functions']), evDivResults=n(N),
             evDivPctDefinite=pct(100 * (c['Neither'] + c['Down'] + c['Up']) / N),
             evDivPctExact=pct(100 * c['Neither'] / N), evDivPctDown=pct(100 * c['Down'] / N),
             evDivPctUp=pct(100 * c['Up'] / N), evDivPctIndet=pct(100 * c['Inconsistent'] / N))

    # where the Indet results come from
    ix = indet_split.compute(f'private-{tag}-returns.txt')
    assert ix['indet'] == ct['indet'], "Indet split must cover the corpus table's Indet column"
    m.update(evIndet=n(ix['indet']), evIndetInherited=n(ix['inherited']),
             evPctIndetInherited=pct(100 * ix['inherited'] / ix['indet']),
             evIndetOrigin=n(ix['origin']), evIndetOriginExact=n(ix['origin_exact']),
             evIndetOriginMixed=n(ix['origin_mixed']))

    # correctness: names
    na = name_agreement.compute(f'private-{tag}-returns.txt')
    m.update(evNameFunctions=n(na['functions']), evNameResults=n(na['results']),
             evNameAgree=n(na['agree']), evNamePct=f"{100 * na['agree'] / na['results']:.0f}\\%",
             evNameMisses=WORDS.get(na['results'] - na['agree'], str(na['results'] - na['agree'])))

    # ablations: the same jar with one feature off (-DdisablePositions, -DdisableIntent)
    for key, flag in (('Pos', 'disablePositions'), ('Intent', 'disableIntent')):
        ab = ablation.compute(f'private-{tag}', f'ablation/{tag}-{flag}')
        h = ab['abl']
        tr = ab['transitions']
        m.update({f'evAbl{key}Results': n(h['results']),
                  f'evAbl{key}PctExact': pct(h['pct']['exact']), f'evAbl{key}PctDown': pct(h['pct']['down']),
                  f'evAbl{key}PctUp': pct(h['pct']['up']), f'evAbl{key}PctIndet': pct(h['pct']['indet']),
                  f'evAbl{key}Exact': n(h['exact']), f'evAbl{key}Down': n(h['down']),
                  f'evAbl{key}Up': n(h['up']), f'evAbl{key}Indet': n(h['indet']),
                  f'evAbl{key}Verdicts': n(ab['verdicts']), f'evAbl{key}Changed': n(ab['changed']),
                  f'evAbl{key}ToIndet': n(sum(v for (a, b), v in tr.items() if b == 'Inconsistent')),
                  f'evAbl{key}UpToDown': n(tr[('Up', 'Down')]),
                  f'evAbl{key}IndetToDown': n(tr[('Inconsistent', 'Down')]),
                  f'evAbl{key}DownToUp': WORDS.get(tr[('Down', 'Up')], str(tr[('Down', 'Up')]))})
        if key == 'Intent':
            intent_ab = ab

    # ------------------------------------------------------------------
    # Qualitative claims the prose makes. Each one is checked here, so a
    # regeneration that makes a sentence false fails instead of shipping it.
    # ------------------------------------------------------------------
    failed = []

    def claim(ok, sentence):
        if not ok:
            failed.append(sentence)

    claim(multi == ['Aave v4', 'Balancer', 'Morpho', 'Tokemak']
          and all(list(PROTOCOL.values()).count(p) == 2 for p in multi),
          "the protocols with two configurations each are Aave v4, Balancer, Morpho, Tokemak")
    claim(all(t == 'TestAaveV4HubValidState' and g == 'Inconsistent'
              for _, _, g, t in na['disagreements']),
          "the name disagreements are all Indet on Aave v4 hub helpers")
    for ok, sentence in case_studies.compute(tag):
        claim(ok, sentence)
    claim(intent_ab['transitions'][('Up', 'Down')] > 0
          and intent_ab['abl']['up'] < intent_ab['full']['up'],
          "without intent recognition, results that round up are read as rounding down")
    claim(case_studies.intent_off(tag) == {'raft': {'Down'}, 'compound_fixed': {'Inconsistent'}},
          "without intent recognition, Raft's divUp reads as Down and Compound's fix as Indet")
    if failed:
        raise SystemExit("PROSE CLAIMS NO LONGER HOLD - fix the text, then regenerate:\n  "
                         + "\n  ".join(failed))

    with open(os.path.join(out, 'eval-numbers.tex'), 'w') as f:
        f.write(f"% GENERATED by scripts/eval/paper_numbers.py {tag} - do not edit\n")
        for k, v in m.items():
            f.write(f"\\newcommand{{\\{k}}}{{{v}\\xspace}}\n")

    # both tables list the configurations by number of results, largest first
    order = [t for t, _ in sorted(ct['rows'].items(), key=lambda kv: -sum(kv[1].values()))]

    # corpus table rows
    with open(os.path.join(out, 'corpus-table.tex'), 'w') as f:
        f.write(f"% GENERATED by scripts/eval/paper_numbers.py {tag} - do not edit\n")
        f.write("\\begin{tabular}{lrrrrrr}\n\\toprule\n"
                "Protocol & Results & \\Exact & \\Down & \\Up & \\Indet & Time (s) \\\\\n"
                "\\midrule\n")
        for t in order:
            row = ct['rows'][t]
            f.write(f"{DISPLAY[t]} & {n(sum(row.values()))} & {n(row['Neither'])} & {n(row['Down'])}"
                    f" & {n(row['Up'])} & {n(row['Inconsistent'])}"
                    f" & {tm['seconds'][t]:.1f} \\\\\n")
        tot = ct['totals']
        f.write("\\midrule\n")
        f.write(f"Total & {n(ct['results'])} & {n(tot['Neither'])} & {n(tot['Down'])} & {n(tot['Up'])}"
                f" & {n(tot['Inconsistent'])} & {tm['total']:.0f} \\\\\n")
        f.write("\\bottomrule\n\\end{tabular}\n")

    # lines-of-code table rows
    with open(os.path.join(out, 'loc-table.tex'), 'w') as f:
        f.write(f"% GENERATED by scripts/eval/paper_numbers.py {tag} - do not edit\n")
        # two side-by-side halves, read down the left half and then the right
        f.write("\\setlength{\\tabcolsep}{3.5pt}\n"
                "\\begin{tabular}{lrr@{\\quad}lrr}\n\\toprule\n"
                "Protocol & Compiled & Analyzed & Protocol & Compiled & Analyzed \\\\\n\\midrule\n")
        half = (len(order) + 1) // 2

        def cells(label, key):
            _, c, a = loc[key]
            return f"{label} & {n(c)} & {n(a)}"
        for i in range(half):
            right = cells(DISPLAY[order[half + i]], order[half + i]) if half + i < len(order) else " & & "
            f.write(f"{cells(DISPLAY[order[i]], order[i])} & {right} \\\\\n")
        f.write("\\midrule\n")
        f.write(f"& & & {cells('Sum over configurations', 'TOTAL')} \\\\\n")
        f.write("\\bottomrule\n\\end{tabular}\n")

    print(f"wrote {len(m)} macros and two tables under {out}")
    for k, v in m.items():
        print(f"  \\{k} = {v}")


if __name__ == '__main__':
    main()

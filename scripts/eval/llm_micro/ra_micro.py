#!/usr/bin/env python3
"""Run RoundAbout on every microbenchmark fixture and read its verdict for each question.

Each fixture is analyzed from its committed AST with its committed conf, exactly as its JUnit
test does (SpecLink fixtures with their spec's links). RoundAbout runs on the original code;
it reads no names except one trusted summary keyed by Math.mulDiv, which the fixtures do not
rely on. A question's verdict is read from the results for its exact definition (file and
first line) in the contexts where every parameter is exact; if those contexts disagree, they
are joined (reported as Indeterminate, and listed).

Run from eval-artifacts/:
  python3 ../scripts/eval/llm_micro/ra_micro.py <fixture-tree> <jar> llm-micro/items.json \
      llm-micro/roundabout-<commit>
"""
import hashlib
import json
import os
import re
import subprocess
import sys

VOCAB = {'Neither': 'Exact', 'Down': 'Down', 'Up': 'Up', 'Inconsistent': 'Indeterminate'}
COMPONENT = re.compile(r'Ltuple, (\d+), <[^>]*> >=(\w+)')


def run(tree, jar, fixture, out):
    base = os.path.join('test', 'data', fixture)
    spec = os.path.join(base, 'SpecLink.json')
    ast = os.path.join(base, 'ast', '.asts.json')
    if os.path.isfile(os.path.join(tree, ast + '.bz2')):  # as AbstractJsonTest picks it
        ast += '.bz2'
    if os.path.isfile(os.path.join(tree, spec)):
        args = [os.path.join(base, 'SpecLink.conf'), out, '--combined-spec', spec, ast]
    else:
        args = [os.path.join(base, 'run.conf'), out, '--combined', ast]
    with open(out + '.log', 'w') as log:
        r = subprocess.run(['java', '-ea', '-jar', jar] + args, cwd=tree, stdout=log, stderr=subprocess.STDOUT)
    if r.returncode != 0:
        raise SystemExit(f'{fixture}: RoundAbout exited {r.returncode}, see {out}.log')


def verdicts(result, item):
    """RoundAbout's verdicts for this question, one per all-exact context."""
    where = re.compile(re.escape(f"/test/data/{item['fixture']}/{item['file']}") + r':\[' + str(item['line']) + ',')
    got = []
    for g in result['graphs']:
        for n in g['nodes'].values():
            m = n['metadata']
            if not where.search(m.get('methodPosition', '')):
                continue
            if any(p.get('rounding') != 'Neither' for p in m.get('parameters', [])):
                continue
            r = m['return']
            if item['component'] is not None:
                comps = dict(COMPONENT.findall(r))
                if len(comps) != item['n_returns']:
                    raise SystemExit(f"{item['id']}: cannot read tuple result {r}")
                r = comps[str(item['component'])]
            elif r.startswith('{'):
                raise SystemExit(f"{item['id']}: tuple result for a single return: {r}")
            got.append(VOCAB[r])
    return got


def join(vs):
    return vs[0] if len(set(vs)) == 1 else 'Indeterminate'


def main():
    tree, jar, items_path, out = sys.argv[1:5]
    tree, jar = os.path.abspath(tree), os.path.abspath(jar)
    items = json.load(open(items_path))
    os.makedirs(out, exist_ok=True)
    results = {}
    for fixture in sorted({i['fixture'] for i in items}):
        path = os.path.abspath(os.path.join(out, fixture + '.json'))
        run(tree, jar, fixture, path)
        results[fixture] = json.load(open(path))
    scores, mixed = {}, []
    for item in items:
        vs = verdicts(results[item['fixture']], item)
        if not vs:
            raise SystemExit(f"{item['id']}: no all-exact result for {item['file']}:{item['line']}")
        if len(set(vs)) > 1:
            mixed.append((item['id'], vs))
        scores[item['id']] = dict(verdict=join(vs), contexts=vs)
    sha = hashlib.sha256(open(jar, 'rb').read()).hexdigest()
    json.dump(dict(jar_sha256=sha, verdicts=scores), open(os.path.join(out, 'verdicts.json'), 'w'), indent=1)
    print(f'jar sha256 {sha}\n{len(scores)} questions read, {len(mixed)} with disagreeing contexts')
    for qid, vs in mixed:
        print(f'  mixed {qid}: {vs}')


if __name__ == '__main__':
    main()

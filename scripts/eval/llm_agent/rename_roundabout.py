#!/usr/bin/env python3
"""RoundAbout on named and renamed copies of the rename study's code bases.

For each code base holding a selected question (select_pure.py), this copies the full
fixture directory twice and runs both copies through one identical pipeline, so the
identifiers are the only difference:

  named/    verbatim
  renamed/  every compiled source rewritten by build_workspaces.anonymize: comments
            removed, identifiers carrying a direction word renamed. Files the agent's
            workspace holds get exactly the workspace's map (checked: the rewritten text
            must equal the workspace's anon/ file byte for byte); identifiers that occur
            only in the other compiled files (certora/ harnesses) get fresh names with a
            different prefix, checked against every identifier in the code base.

Each copy gets a conf: the original's options, `verify` replaced by
`<Contract>:trivial.spec`, `solc` pinned (table below), contract names in `verify`,
`link` and `parametric_contracts` mapped for the renamed copy. Then, in the copy:
  certoraRun <conf> --build_only --dump_asts --disable_local_typechecking
      --ignore_solidity_warnings --disable_internal_function_instrumentation
  java -ea -jar <jar> <conf> <out.json> --combined <asts>

Verdicts are read per question by exact file + contract + signature (mapped through the
rename table for the renamed copy), from contexts where every parameter is exact. Checks,
all reported, none papered over:
  - named copy vs the frozen run, for every function with an all-exact context
  - after compiling, the renamed copy's sources hold no identifier with a direction word,
    and both copies compiled the same file set as the frozen run's ASTs
  - named vs renamed, per question

Run from eval-artifacts/ with a Python that has json5 (certora-cli's venv does):
  rename_roundabout.py <items.json> <selection.json> <env priv root> <workspaces> <jar>
      <out dir> <certoraRun> [--only SLUG ...]
"""
import argparse
import bz2
import glob
import json
import os
import re
import shutil
import subprocess
import sys

import json5

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, '..'))
from build_items import LABEL, POS, functions, verdict  # noqa: E402
from build_workspaces import DIRWORD, TOKEN, anonymize, slug, sources  # noqa: E402
from corpus import CORPUS  # noqa: E402

SOLC = {
    'test/data/AaveV4/HubValidState': 'solc8.28',
    'test/data/AaveV4/Liquidation': 'solc8.28',
    'test/data/InfiniFi/MintController': 'solc8.28',
    'test/data/EigenLayer/EigenPodManagerRules': 'solc8.27',
    'test/data/MorphoV2/Midnight': 'solc8.34',
    'test/data/MorphoV2/SharePrice': 'solc8.31',
    'test/data/Cork/0Auxiliary': 'solc8.30',
    'test/data/CozyEuler/TrancheRaiseStrategy': 'solc8.22',
    'test/data/VedaBoring/AccountantWithRateProviders': 'solc8.21',
    'test/data/BalancerV2/stablePool/4f189ea1': 'solc7.6',
    'test/data/BalancerV2/stablePool/PaminaNov25': 'solc7.1',
}
EXTRA_PREFIX = 'rid'  # names for identifiers outside the workspace; the workspace uses 'id'
IDENT = re.compile(r'[A-Za-z_$][A-Za-z0-9_$]*')
CERTORA_FLAGS = ['--build_only', '--dump_asts', '--disable_local_typechecking',
                 '--ignore_solidity_warnings', '--disable_internal_function_instrumentation']


def idents(text):
    return {m.group(0) for m in TOKEN.finditer(text) if m.group(0)[0].isalpha() or m.group(0)[0] in '_$'}


def rename_extra(texts, renames, used):
    """Rewrite non-workspace sources with the workspace map, extended with fresh names."""
    extra, n = {}, 0

    def sub(m):
        nonlocal n
        tok = m.group(0)
        if tok.startswith(('"', "'")):
            return tok
        if tok.startswith('//'):
            return ''
        if tok.startswith('/*'):
            return '\n' * tok.count('\n')
        if DIRWORD.search(tok):
            if tok not in renames and tok not in extra:
                while True:
                    n += 1
                    cand = f'{EXTRA_PREFIX}{n}'
                    if cand not in used:
                        break
                extra[tok] = cand
            return renames.get(tok) or extra[tok]
        return tok

    return {p: TOKEN.sub(sub, t) for p, t in texts.items()}, extra


def mapper(table):
    return lambda s: IDENT.sub(lambda m: table.get(m.group(0), m.group(0)), s)


def write_confs(orig, copy_dir, root, rename, units):
    """The analysis conf (the original, `verify` replaced, `solc` pinned, names mapped) and
    the build conf certoraRun compiles from: the same, but listing exactly the compilation
    units of the frozen run's ASTs. They differ only where the frozen ASTs were built from
    another file list than the conf's (BalancerV2 4f189ea1, whose conf names a harness
    that no longer compiles); the frozen run analyzed those ASTs with this conf."""
    conf = dict(orig)
    contract = conf['verify'].split(':', 1)[0]
    conf['verify'] = f"{rename(contract)}:trivial.spec"
    conf['solc'] = SOLC[root]
    for k in ('link', 'struct_link'):
        if k in conf:
            conf[k] = [rename(x) for x in conf[k]]
    if 'parametric_contracts' in conf:
        pc = conf['parametric_contracts']
        conf['parametric_contracts'] = [rename(x) for x in pc] if isinstance(pc, list) else rename(pc)
    # options about rules of the replaced spec, or prover-only checks that certoraRun refuses
    # to combine with --disable_internal_function_instrumentation
    for k in ('exclude_rule', 'rule', 'rule_sanity', 'assert_autofinder_success'):
        conf.pop(k, None)
    analysis = os.path.join(copy_dir, 'rename-study.conf')
    json.dump(conf, open(analysis, 'w'), indent=2)
    build = analysis
    by_path = {f.split(':', 1)[0]: f for f in conf['files']}  # entries may be path:Contract
    if sorted(by_path) != sorted(units):
        bconf = dict(conf, files=[by_path.get(u, u) for u in units])
        src = '\n'.join(open(os.path.join(copy_dir, u), errors='replace').read() for u in units)
        if not re.search(r'\bcontract\s+' + re.escape(rename(contract)) + r'\b', src):
            first = re.search(r'\bcontract\s+(\w+)', open(os.path.join(copy_dir, units[0])).read())
            bconf['verify'] = f"{first.group(1)}:trivial.spec"
        bconf.pop('link', None)
        build = os.path.join(copy_dir, 'rename-study-build.conf')
        json.dump(bconf, open(build, 'w'), indent=2)
    with open(os.path.join(copy_dir, 'trivial.spec'), 'w') as f:
        f.write('rule trivial { assert true; }\n')
    return analysis, build


def frozen_units(src, root):
    """The frozen ASTs' compilation units, relative to the fixture directory (some ASTs
    record them from the repository root instead)."""
    for name in ('.asts.json.bz2', '.asts.json'):
        p = os.path.join(src, 'ast', name)
        if os.path.isfile(p):
            units = json.load(bz2.open(p, 'rt') if name.endswith('.bz2') else open(p)).keys()
            return [u.split(root.rstrip('/') + '/', 1)[-1].removeprefix('./') for u in units]
    raise SystemExit(f"no frozen ASTs under {src}/ast")


def run(cmd, cwd, log, env=None):
    with open(log, 'w') as lf:
        lf.write(' '.join(cmd) + '\n\n')
        lf.flush()
        return subprocess.run(cmd, cwd=cwd, stdout=lf, stderr=subprocess.STDOUT, env=env).returncode


def newest_asts(copy_dir):
    c = sorted(glob.glob(os.path.join(copy_dir, '.certora_internal', '*', '.asts.json')), key=os.path.getmtime)
    return c[-1] if c else None


def compiled_files(asts, copy_dir):
    d = json.load(open(asts))
    out = set()
    for unit in d.values():
        for s in unit:
            s = os.path.normpath(s if os.path.isabs(s) else os.path.join(copy_dir, s))
            out.add(os.path.relpath(s, copy_dir))
    return out


def verdicts(out_json, copy_dir):
    """{(file, contract, signature): {verdict}} over all-exact contexts, read as
    build_items.functions reads the frozen run; file is relative to the copy."""
    real_copy = os.path.realpath(copy_dir)
    found = {}
    for g in json.load(open(out_json)).get('graphs', []):
        md = (g.get('nodes', {}).get('0') or {}).get('metadata') or {}
        lab, pos = LABEL.match(g.get('label', '')), POS.match(md.get('methodPosition') or '')
        if not lab or not pos or md.get('method') is None:
            continue
        if not all(q.get('rounding') == 'Neither' for q in md.get('parameters', [])):
            continue
        p = pos.group(1)
        p = os.path.realpath(p if os.path.isabs(p) else os.path.join(copy_dir, p))
        if not p.startswith(real_copy + os.sep):
            continue
        key = (os.path.relpath(p, real_copy), lab.group(1), f"{lab.group(2)}({lab.group(3)})")
        found.setdefault(key, set()).add(verdict(md))
    return found


def main():
    ap = argparse.ArgumentParser()
    for a in ('items', 'selection', 'env', 'workspaces', 'jar', 'out', 'certora_run'):
        ap.add_argument(a)
    ap.add_argument('--only', nargs='*')
    a = ap.parse_args()

    items = {i['id']: i for i in json.load(open(a.items))}
    sel = [items[i] for i in json.load(open(a.selection))['ids']]
    roots = sorted({i['root'] for i in sel})
    test_of = {d: t for t, d, _, _ in CORPUS}
    conf_of = {d: c for _, d, c, _ in CORPUS}
    env = dict(os.environ)
    env['PATH'] = os.pathsep.join([os.path.expanduser('~/research/CVT-Executables-Mac'),
                                   os.path.abspath('tools/solc'),
                                   os.path.dirname(os.path.abspath(a.certora_run)), env['PATH']])
    jar = os.path.abspath(a.jar)
    report = dict(questions=[], codebases={})
    os.makedirs(a.out, exist_ok=True)

    for root in roots:
        s = slug(root)
        if a.only and s not in a.only:
            continue
        test = test_of[root]
        src = os.path.join(a.env, root)
        files = sources(src)
        ws_files = [f for f in files if not f.startswith('certora/')]
        other = [f for f in files if f.startswith('certora/')]
        texts = {f: open(os.path.join(src, f), errors='replace').read() for f in files}

        # the workspace's own map, recomputed exactly as build_workspaces does
        ws_anon, renames = anonymize({f: texts[f] for f in ws_files})
        saved = json.load(open(os.path.join(a.workspaces, s, 'anon-map.json')))['renames']
        if renames != saved:
            raise SystemExit(f"{s}: recomputed rename map differs from the workspace's")
        for f in ws_files:
            if ws_anon[f] != open(os.path.join(a.workspaces, s, 'anon', f), errors='replace').read():
                raise SystemExit(f"{s}/{f}: rewritten text differs from the workspace's anon file")
        used = set().union(*(idents(t) for t in texts.values())) | set(renames.values())
        clash = sorted(v for v in renames.values() if any(v in idents(texts[f]) for f in other))
        if clash:
            raise SystemExit(f"{s}: workspace names {clash} already occur in harness files")
        other_anon, extra = rename_extra({f: texts[f] for f in other}, renames, used)
        table = {**renames, **extra}
        rename = mapper(table)

        orig_conf = json5.load(open(os.path.join(src, conf_of[root])))
        units = frozen_units(src, root)
        cb = dict(renames_workspace=len(renames), renames_extra=extra, copies={})
        for cond in ('named', 'renamed'):
            d = os.path.abspath(os.path.join(a.out, s, cond))
            if os.path.exists(d):
                raise SystemExit(f"{d} exists: results are never regenerated in place")
            shutil.copytree(src, d, symlinks=True, ignore=shutil.ignore_patterns('.certora_internal', 'ast'))
            if cond == 'renamed':
                for f, t in {**ws_anon, **other_anon}.items():
                    with open(os.path.join(d, f), 'w') as fh:
                        fh.write(t)
            conf, build = write_confs(orig_conf, d, root, rename if cond == 'renamed' else (lambda x: x),
                                      units)
            rc = run([a.certora_run, os.path.basename(build)] + CERTORA_FLAGS, d,
                     os.path.join(d, 'certoraRun.log'), env)
            asts = newest_asts(d)  # certoraRun can exit 1 late although the AST dump succeeded
            if not asts:
                raise SystemExit(f"{s}/{cond}: no ASTs (certoraRun rc={rc}); see {d}/certoraRun.log")
            out_json = os.path.join(d, 'roundabout.json')
            rc2 = run(['java', '-ea', '-jar', jar, os.path.basename(conf), out_json, '--combined', asts],
                      d, os.path.join(d, 'roundabout.log'), env)
            if rc2 != 0 or not os.path.isfile(out_json):
                raise SystemExit(f"{s}/{cond}: RoundAbout failed (rc={rc2}); see {d}/roundabout.log")
            comp = compiled_files(asts, d)
            leftover = sorted({t for f in comp if f.endswith('.sol')
                               for t in idents(open(os.path.join(d, f), errors='replace').read())
                               if DIRWORD.search(t)}) if cond == 'renamed' else []
            cb['copies'][cond] = dict(certora_rc=rc, built_from_frozen_units=build != conf, compiled=len(comp),
                                      compiled_same_as_frozen=comp == set(files),
                                      only_in_copy=sorted(comp - set(files))[:20],
                                      only_in_frozen=sorted(set(files) - comp)[:20],
                                      direction_words_left=leftover,
                                      verdicts={f'{k[0]}|{k[1]}|{k[2]}': sorted(v) for k, v in
                                                verdicts(out_json, d).items()})

        # named vs frozen, every all-exact function
        frozen = {(k[1], k[2], k[3]): e['verdicts'] for k, e in
                  functions(sorted(glob.glob(f'private-frozen/{test}_*.json')), root, test).items()}
        named = {tuple(k.split('|')): set(v) for k, v in cb['copies']['named']['verdicts'].items()}
        cb['named_vs_frozen'] = dict(
            compared=len(set(frozen) & set(named)),
            differ=sorted(f'{k} frozen={sorted(frozen[k])} named={sorted(named[k])}'
                          for k in set(frozen) & set(named) if frozen[k] != named[k]),
            only_frozen=len(set(frozen) - set(named)), only_named=len(set(named) - set(frozen)))
        renamed = {tuple(k.split('|')): set(v) for k, v in cb['copies']['renamed']['verdicts'].items()}
        for it in (i for i in sel if i['root'] == root):
            kn = (it['file'], it['contract'], it['signature'])
            kr = (it['file'], rename(it['contract']), rename(it['signature']))
            report['questions'].append(dict(
                id=it['id'], label=it['label'], frozen=it['tool'],
                named=sorted(named.get(kn, [])), renamed=sorted(renamed.get(kr, [])),
                renamed_signature=f'{kr[1]}.{kr[2]}'))
        report['codebases'][s] = cb
        nv = cb['named_vs_frozen']
        print(f"{s:45s} compiled named={cb['copies']['named']['compiled_same_as_frozen']} "
              f"renamed={cb['copies']['renamed']['compiled_same_as_frozen']}  "
              f"left={len(cb['copies']['renamed']['direction_words_left'])}  "
              f"named-vs-frozen {nv['compared']} compared, {len(nv['differ'])} differ", flush=True)

    path = os.path.join(a.out, 'results.json' if not a.only else f"results-{'-'.join(a.only)}.json")
    json.dump(report, open(path, 'w'), indent=1)
    print(f"wrote {path}")


if __name__ == '__main__':
    main()

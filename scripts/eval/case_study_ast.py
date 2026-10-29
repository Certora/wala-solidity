#!/usr/bin/env python3
"""Dump the solc ASTs of one case-study configuration, as roundabout.py does.

The case studies have no committed AST, so each run compiles them: certoraRun
--dump_asts --build_only on the configuration, with its spec replaced by a trivial
one (only the compilation is needed). certoraRun can exit nonzero late in
--build_only after the dump succeeded, so success is judged by a fresh
.certora_internal/latest/.asts.json, which is copied to <out-asts>.

Run from the suite environment's root (the conf's paths are relative to it).

Usage: case_study_ast.py <certoraRun> <conf> <out-asts>
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

certora_run, conf, out = sys.argv[1:4]
data = json.load(open(conf))
contract = data['verify'].split(':')[0]
tmp = tempfile.mkdtemp(prefix='case_study_ast_')
spec = os.path.join(tmp, 'trivial.spec')
with open(spec, 'w') as f:
    f.write('rule trivial { assert true; }\n')
data['verify'] = f'{contract}:{spec}'
dump_conf = os.path.join(tmp, 'dump.conf')
with open(dump_conf, 'w') as f:
    json.dump(data, f, indent=4)

asts = '.certora_internal/latest/.asts.json'
start = time.time()
r = subprocess.run([certora_run, dump_conf, '--dump_asts', '--build_only',
                    '--disable_local_typechecking', '--ignore_solidity_warnings',
                    '--disable_internal_function_instrumentation'],
                   capture_output=True, text=True)
if not os.path.isfile(asts) or os.path.getmtime(asts) < start:
    sys.stderr.write(r.stdout + r.stderr)
    sys.exit(f'{conf}: certoraRun wrote no fresh {asts} (exit {r.returncode})')
os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
shutil.copyfile(asts, out)
shutil.rmtree(tmp)
print(f'{conf}: ASTs -> {out} (certoraRun exit {r.returncode})')

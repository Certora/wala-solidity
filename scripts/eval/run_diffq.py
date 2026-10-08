#!/usr/bin/env python3
"""Manifest-driven differential-harness runner.

Runs eval.DifferentialQ for every configuration in corpus.py, with per-run logs and
no silent failure: a run that exits nonzero or produces no TSV is reported by name,
and the script fails unless all 21 complete.

Usage: python3 run_diffq.py <suite-env-root> <jar> <out-dir> [samples] [harness flags...]
  e.g. ... 2000 --all-nodes --search=20000
"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from corpus import CORPUS, tag

root, jar, out = (os.path.abspath(a) for a in sys.argv[1:4])
samples = sys.argv[4] if len(sys.argv) > 4 else "2000"
flags = sys.argv[5:]
os.makedirs(out, exist_ok=True)
failed = []
for t, d, conf, ast in CORPUS:
    astp = os.path.join(d, ast)
    if not os.path.isfile(os.path.join(root, astp)):
        astp = astp.removesuffix('.bz2')
    tsv = os.path.join(out, tag(t) + '.tsv')
    log = os.path.join(out, tag(t) + '.log')
    with open(log, 'w') as lf:
        r = subprocess.run(
            ["java", "-ea", "-cp", jar, "com.certora.wala.analysis.rounding.eval.DifferentialQ",
             os.path.join(d, conf), tsv, "--combined", astp, samples, *flags],
            cwd=root, stdout=lf, stderr=lf)
    ok = r.returncode == 0 and os.path.isfile(tsv) and len(open(tsv).read().splitlines()) > 1
    print(f"{'ok  ' if ok else 'FAIL'} {t}")
    if not ok:
        failed.append(t)
print()
if failed:
    print(f"{len(failed)} of {len(CORPUS)} configurations FAILED: {failed}")
    raise SystemExit(1)
print(f"all {len(CORPUS)} configurations completed")

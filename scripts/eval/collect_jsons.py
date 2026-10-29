#!/usr/bin/env python3
"""Collect the result JSONs of one suite run from its log.

Each JSON test prints the temp path of the JSON it wrote, after surefire's
'Running <class>' line. This copies that file to <out-dir>/<TestName>_<k>.json
(k counts the JSONs one test wrote, from 1) and lists the tests that failed.

Fails if a printed JSON no longer exists, or if a corpus configuration wrote
no JSON. Run it right after the suite run: the files live in the temp directory.

Usage: collect_jsons.py <mvn-test.log> <out-dir>
"""
import os
import re
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from corpus import PROT

RUNNING = re.compile(r'\[INFO\] Running \S*\.(Test\w+)$')
JSON = re.compile(r'(/\S+/roundAbout\d+\.json)$')
FAILED = re.compile(r'<<< (?:FAILURE|ERROR)! -- in \S*\.(Test\w+)$')


def collect(log, out):
    os.makedirs(out, exist_ok=True)
    test, written, failed = None, {}, []
    for line in open(log, errors='replace'):
        line = line.rstrip('\n')
        m = RUNNING.search(line)
        if m:
            test = m.group(1)
            continue
        m = FAILED.search(line)
        if m:
            failed.append(m.group(1))
            continue
        m = JSON.fullmatch(line)
        if m and test:
            src = m.group(1)
            if not os.path.isfile(src):
                sys.exit(f'{log}: {test} wrote {src}, which no longer exists')
            k = written.get(test, 0) + 1
            written[test] = k
            shutil.copyfile(src, os.path.join(out, f'{test}_{k}.json'))
    missing = sorted(t for t in PROT if t not in written)
    if missing:
        sys.exit(f'{log}: no JSON for corpus configuration(s) {", ".join(missing)}')
    return written, failed


if __name__ == '__main__':
    written, failed = collect(sys.argv[1], sys.argv[2])
    print(f'{sum(written.values())} JSONs from {len(written)} tests -> {sys.argv[2]}')
    print('failed tests: ' + (', '.join(failed) if failed else 'none'))

#!/usr/bin/env python3
"""Per-configuration analysis times from a suite run log (surefire 'Time elapsed').

The time of a corpus test is the whole analysis of that configuration - IR
construction, call graph, both phases - plus the test's assertions, which are
negligible. Fails if any corpus configuration has no timing line.

Usage: timings.py <mvn-test.log>
"""
import os
import re
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from corpus import PROT

LINE = re.compile(r'Time elapsed: ([0-9.]+) s -- in \S*\.(Test\w+)$')


def compute(log):
    t = {}
    for line in open(log, errors='replace'):
        m = LINE.search(line.rstrip())
        if m and m.group(2) in PROT:
            t[m.group(2)] = float(m.group(1))
    missing = sorted(PROT - set(t))
    if missing:
        raise SystemExit(f"no timing for corpus tests: {missing}")
    slowest = max(t, key=t.get)
    return dict(seconds=t, median=statistics.median(t.values()),
                slowest=slowest, slowest_s=t[slowest], total=sum(t.values()))


def main():
    r = compute(sys.argv[1])
    for k, v in sorted(r['seconds'].items(), key=lambda kv: -kv[1]):
        print(f"{k:36s} {v:7.1f}")
    print(f"median {r['median']:.1f}s  slowest {r['slowest']} {r['slowest_s']:.1f}s  "
          f"total {r['total']:.0f}s ({r['total'] / 60:.1f} min)")


if __name__ == '__main__':
    main()

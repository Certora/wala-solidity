#!/usr/bin/env python3
"""Count the reports position inference removes: compare a configuration's result
JSON with position inference on (normal run) and off (-DdisablePositions=true).

A 'report' is a flagged rounding site: (method, context, source position). Reports
present only in the positions-off run are the ones position inference removes.

Usage: ablation_positions.py <positions-on.json> <positions-off.json>
Produce the inputs with:
  java -ea [-DdisablePositions=true] -cp <jar> com.certora.roundAbout.RoundAbout \
       <conf> <out.json> --combined <asts>
"""
import json
import sys


def _sites(path):
    sites = set()
    for g in json.load(open(path)).get('graphs', []):
        for node in g.get('nodes', {}).values():
            md = node.get('metadata') or {}
            if md.get('method') is None:
                continue
            ctx = ','.join(p.get('rounding', '') for p in md.get('parameters', []))
            for pos, s in (md.get('roundings') or {}).items():
                sites.add((md['method'], ctx, pos, s.get('rounding'), s.get('source', '')))
    return sites


def compute(on, off):
    son, soff = _sites(on), _sites(off)
    added = soff - son
    return dict(sites_on=len(son), sites_off=len(soff), removed_by_positions=len(added),
                functions=len({m for m, _, _, _, _ in added}),
                appeared_only_with_positions=len(son - soff))


def main():
    r = compute(sys.argv[1], sys.argv[2])
    print(f"sites with positions {r['sites_on']}, without {r['sites_off']}")
    print(f"position inference removes {r['removed_by_positions']} sites "
          f"across {r['functions']} functions "
          f"({r['appeared_only_with_positions']} sites appear only with positions on)")


if __name__ == '__main__':
    main()

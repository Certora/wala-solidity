#!/bin/sh
# Build the private evaluation environment from zero.
#
# Usage: make_env.sh <wala-solidity repo> <roundabout-tests repo> <out-dir> [rev] [tests-rev]
#
# The result is a clone of wala-solidity at <rev>, a branch or commit (default
# experiment/phase1-phase2-split), overlaid with roundabout-tests' test sources and
# data taken from its <tests-rev> *ref* (default origin/HEAD; git archive), so a
# stale working tree cannot leak in. Fails on any missing piece; verifies the corpus manifest at the end.
set -eu
WALA=$1; RT=$2; OUT=$3; REV=${4:-experiment/phase1-phase2-split}; RT_REV=${5:-origin/HEAD}
test -d "$WALA/.git" || { echo "not a git repo: $WALA" >&2; exit 1; }
test -d "$RT/.git" || { echo "not a git repo: $RT" >&2; exit 1; }
rm -rf "$OUT"; mkdir -p "$OUT/rt"
( cd "$RT" && git archive "$RT_REV" ) | tar -x -C "$OUT/rt"
git -C "$WALA" worktree list >/dev/null  # sanity that git works here
git clone -q "$WALA" "$OUT/priv"
git -C "$OUT/priv" checkout -q "$REV"
cp -R "$OUT/rt/test/src/com/certora/wala/cast/solidity/test/json/." \
      "$OUT/priv/test/src/com/certora/wala/cast/solidity/test/json/"
cp -R "$OUT/rt/test/data/." "$OUT/priv/test/data/"
rm -rf "$OUT/rt"
echo "environment at $OUT/priv ($(git -C "$OUT/priv" log --oneline -1); tests $(git -C "$RT" rev-parse --short "$RT_REV"))"
python3 "$(dirname "$0")/corpus.py" "$OUT/priv"

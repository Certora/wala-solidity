#!/bin/bash
# Redo the corpus evaluation from zero: environment, timed suite runs, result JSONs,
# extracts, lines of code, both ablations, the case studies, and the paper's macros
# and tables. Every step's output lands under <out-dir>; nothing outside it is written.
#
# Usage: run_eval.sh <out-dir> <wala-rev> <tests-rev> [tag]
#
#   out-dir    must not exist; gets env/ (the suite environment), artifacts/ (laid out
#              like eval-artifacts/, plus paper/generated/) and run_eval.log
#   wala-rev   the tool commit to evaluate, e.g. 2ee3816 (the submitted paper)
#   tests-rev  the roundabout-tests commit holding the corpus, e.g. 496d18e
#   tag        names the outputs (private-<tag>/, runs/<tag>-mvn-test-<i>.log, ...);
#              default: the short hash of <wala-rev>
#
# Environment (defaults in brackets):
#   RT            roundabout-tests clone [~/research/roundabout-tests]
#   JAVA_HOME     the JDK for every run [~/Library/Java/JavaVirtualMachines/openjdk-21.0.1/Contents/Home]
#   CERTORA_RUN   certoraRun with --dump_asts, for the case studies' ASTs
#                 [~/research/AutoProver/.venv/bin/certoraRun]; solc<ver> binaries on PATH
#   CASE_STUDIES  directory holding CompoundV2Actual/, CompoundV2Fixed/, RaftActual/
#                 [<this repo>/test/data/_repros]; the submitted ones are in roundabout-tests,
#                 branch oopsla-eval/2026-10-15_2ee3816, oopsla-eval/2026-10-15_2ee3816/case-studies/inputs/
#   RUNS          number of timed suite runs [3]
#   REFERENCE     optional: a directory laid out like eval-artifacts/ (or a snapshot with
#                 generated/) to diff the new outputs against
#   ALLOW_BATTERY set to 1 to time runs on battery or in Low Power Mode (not for the paper)
#
# Example, the submitted paper's evaluation:
#   scripts/eval/run_eval.sh /tmp/eval-2ee3816 2ee3816 496d18e
set -euo pipefail

[ $# -ge 3 ] || { sed -n '2,29p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
HERE=$(cd "$(dirname "$0")" && pwd)
WALA=$(cd "$HERE/../.." && pwd)
OUT=$1; REV=$2; RT_REV=$3
TAG=${4:-$(git -C "$WALA" rev-parse --short "$REV")}
RT=${RT:-$HOME/research/roundabout-tests}
export JAVA_HOME=${JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/openjdk-21.0.1/Contents/Home}
export PATH="$JAVA_HOME/bin:$PATH"
CERTORA_RUN=${CERTORA_RUN:-$HOME/research/AutoProver/.venv/bin/certoraRun}
CASE_STUDIES=${CASE_STUDIES:-$WALA/test/data/_repros}
RUNS=${RUNS:-3}
CASES="CompoundV2Actual CompoundV2Fixed RaftActual"
# ablation flag -> the name the case-study outputs use
ABLATIONS="disablePositions:positions-off disableIntent:intent-off"

die() { echo "run_eval: $*" >&2; exit 1; }
[ ! -e "$OUT" ] || die "$OUT exists; give a new directory"
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
ENV=$OUT/env; PRIV=$ENV/priv; ART=$OUT/artifacts; TMP=$OUT/tmp
log() { echo "[run_eval $(date '+%F %T')] $*" | tee -a "$OUT/run_eval.log"; }
py() { python3 "$HERE/$1" "${@:2}"; }

# --- preflight: fail before any long step ----------------------------------------
"$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21' || die "JAVA_HOME is not a JDK 21: $JAVA_HOME"
command -v mvn >/dev/null || die "mvn not on PATH"
[ -x "$CERTORA_RUN" ] || die "CERTORA_RUN not executable: $CERTORA_RUN"
git -C "$RT" rev-parse -q --verify "$RT_REV^{commit}" >/dev/null || die "no commit $RT_REV in $RT"
git -C "$WALA" rev-parse -q --verify "$REV^{commit}" >/dev/null || die "no commit $REV in $WALA"
for b in $CASES; do [ -f "$CASE_STUDIES/$b/run.conf" ] || die "missing $CASE_STUDIES/$b/run.conf"; done
power_state() {
    if command -v pmset >/dev/null; then
        echo "power $(pmset -g batt | head -1 | sed "s/.*'\(.*\)'.*/\1/"); lowpowermode $(pmset -g | awk '/lowpowermode/ {print $2}')"
    else
        echo "power unknown"
    fi
}
check_power() {
    [ "${ALLOW_BATTERY:-0}" = 1 ] && return
    case "$(power_state)" in
        "power AC Power; lowpowermode 0"|"power unknown") ;;
        *) die "timed runs need AC power and Low Power Mode off ($(power_state)); ALLOW_BATTERY=1 overrides" ;;
    esac
}
check_power
mkdir -p "$ART"/{runs,loc,ablation,case-studies/asts,case-studies/ablation-$TAG,paper} "$TMP"
log "out $OUT, tool $REV, tests $RT_REV, tag $TAG, $RUNS timed runs"

# --- 1. environment and jar ----------------------------------------------------------
log "1/7 environment"
sh "$HERE/make_env.sh" "$WALA" "$RT" "$ENV" "$REV" "$RT_REV" 2>&1 | tee -a "$OUT/run_eval.log"
( cd "$PRIV" && mvn -B -o -q package -DskipTests ) > "$OUT/build.log" 2>&1 || die "build failed, see $OUT/build.log"
JAR=$PRIV/target/roundabout-0.0.1-SNAPSHOT.jar
[ -f "$JAR" ] || die "no jar at $JAR"
MACHINE=$ART/runs/$TAG-machine.txt
{
    echo "commit $(git -C "$PRIV" log --oneline -1)"
    echo "roundabout-tests $(git -C "$RT" rev-parse --short "$RT_REV")"
    echo "scripts $(git -C "$WALA" rev-parse --short HEAD)$(git -C "$WALA" diff --quiet HEAD -- scripts/eval || echo ' (with uncommitted changes)')"
    echo "jar sha256 $(shasum -a 256 "$JAR" | cut -d' ' -f1)"
    if [ "$(uname)" = Darwin ]; then
        echo "$(sysctl -n machdep.cpu.brand_string), cores $(sysctl -n hw.ncpu), memory $(( $(sysctl -n hw.memsize) / 1073741824 )) GB"
        echo "$(sw_vers -productName) $(sw_vers -productVersion)"
    else
        uname -a
    fi
    echo "java (every run): $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
    echo "certoraRun: $CERTORA_RUN"
} > "$MACHINE"

# --- 2. timed suite runs -----------------------------------------------------------
suite() {  # suite <log> [JAVA_TOOL_OPTIONS]: one full suite run; failing tests are listed, not fatal
    ( cd "$PRIV" && JAVA_TOOL_OPTIONS=${2:-} mvn -B -o test ) > "$1" 2>&1 || true
    grep -qE '^\[(INFO|ERROR|WARNING)\] Tests run: [0-9]+, Failures' "$1" \
        || die "the suite did not finish, see $1"
}
for i in $(seq 1 "$RUNS"); do
    check_power
    echo "run $i: $(date '+%F %T'), $(power_state), load $(sysctl -n vm.loadavg 2>/dev/null || cat /proc/loadavg)" >> "$MACHINE"
    log "2/7 timed suite run $i of $RUNS"
    suite "$ART/runs/$TAG-mvn-test-$i.log"
    py collect_jsons.py "$ART/runs/$TAG-mvn-test-$i.log" "$TMP/run-$i" | tee -a "$OUT/run_eval.log"
    py extract.py "$TMP/run-$i" "$TMP/run-$i" > /dev/null
done

# --- 3. result JSONs and extracts ---------------------------------------------------
log "3/7 result JSONs (run 1) and extracts; every run must give the same extracts"
for i in $(seq 2 "$RUNS"); do
    for x in returns roots roundings; do
        cmp -s "$TMP/run-1-$x.txt" "$TMP/run-$i-$x.txt" || die "runs 1 and $i differ ($x): the analysis is not deterministic"
    done
done
cp -R "$TMP/run-1" "$ART/private-$TAG"
py extract.py "$ART/private-$TAG" "$ART/private-$TAG" | tee -a "$OUT/run_eval.log"
python3 - "$HERE" "$ART"/runs/"$TAG"-mvn-test-*.log <<'EOF' | tee -a "$OUT/run_eval.log"
import sys; sys.path.insert(0, sys.argv[1]); import timings
totals = [timings.compute(log)['total'] for log in sys.argv[2:]]
print('run totals (s): ' + ', '.join(f'{t:.1f}' for t in totals))
if max(totals) > 1.15 * min(totals):
    print('WARNING: the runs differ by more than 15%; check the machine was quiet before using the times')
EOF

# --- 4. lines of code ------------------------------------------------------------------
log "4/7 lines of code"
py loc.py "$PRIV" "$ART/private-$TAG" "$ART/loc/$TAG-loc.tsv" | tee -a "$OUT/run_eval.log"

# --- 5. ablations: the same jar with one feature off --------------------------------------
for a in $ABLATIONS; do
    flag=${a%%:*}
    log "5/7 ablation -D$flag=true"
    L=$ART/ablation/$TAG-$flag-mvn-test.log
    suite "$L" "-D$flag=true"
    grep -q "Picked up JAVA_TOOL_OPTIONS: -D$flag=true" "$L" || die "-D$flag did not reach the JVM, see $L"
    py collect_jsons.py "$L" "$ART/ablation/$TAG-$flag-json" | tee -a "$OUT/run_eval.log"
    py extract.py "$ART/ablation/$TAG-$flag-json" "$ART/ablation/$TAG-$flag" > /dev/null
done

# --- 6. case studies: compile each, then the full jar and both ablations ----------------
log "6/7 case studies"
run_case() {  # run_case <case> <asts> <out.json> [JAVA_TOOL_OPTIONS]
    ( cd "$PRIV" && JAVA_TOOL_OPTIONS=${4:-} java -ea -jar "$JAR" "test/data/_repros/$1/run.conf" "$3" --combined "$2" ) \
        > "$3.log" 2>&1 || die "case study $1 failed, see $3.log"
}
mkdir -p "$PRIV/test/data/_repros"
for b in $CASES; do
    cp -R "$CASE_STUDIES/$b" "$PRIV/test/data/_repros/"
    A=$ART/case-studies/asts/$b.asts.json
    ( cd "$PRIV" && python3 "$HERE/case_study_ast.py" "$CERTORA_RUN" "test/data/_repros/$b/run.conf" "$A" ) \
        | tee -a "$OUT/run_eval.log"
    run_case "$b" "$A" "$ART/case-studies/cs-$b.json"
    for a in $ABLATIONS; do
        run_case "$b" "$A" "$ART/case-studies/ablation-$TAG/${a#*:}-cs-$b.json" "-D${a%%:*}=true"
    done
done

# --- 7. the paper's macros and tables, with every claim check ------------------------------
log "7/7 paper numbers"
( cd "$ART" && python3 "$HERE/paper_numbers.py" "$TAG" "$ART/paper" ) > "$OUT/paper_numbers.log" 2>&1 \
    || { cat "$OUT/paper_numbers.log"; die "paper_numbers.py failed: a claim does not hold"; }
cat "$OUT/paper_numbers.log" >> "$OUT/run_eval.log"

if [ -n "${REFERENCE:-}" ]; then
    log "differences from $REFERENCE (timing macros are expected to differ)"
    ( cd "$ART" && for f in private-$TAG-{returns,roots,roundings}.txt loc/$TAG-loc.tsv \
                         ablation/$TAG-*-{returns,roots,roundings}.txt; do
          if [ -f "$REFERENCE/$f" ]; then
              cmp -s "$f" "$REFERENCE/$f" && echo "same      $f" || echo "DIFFERENT $f"
          fi
      done
      for f in paper/generated/*.tex; do
          g=$REFERENCE/generated/$(basename "$f")
          if [ -f "$g" ]; then
              diff -u "$g" "$f" > "$OUT/diff-$(basename "$f").txt" && echo "same      $f" \
                  || echo "DIFFERENT $f (see $OUT/diff-$(basename "$f").txt)"
          fi
      done ) | tee -a "$OUT/run_eval.log"
fi
rm -rf "$TMP"
log "done: outputs in $ART, macros and tables in $ART/paper/generated"

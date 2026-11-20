# Phase 1 idioms as egglog rules (prototype)

RoundAbout's phase 1 finds the rounding idioms of the paper's Table 1 with
hand-written Java matchers (`RoundingRecognition`). This directory holds the
bias, indicator, masked and flag idioms as egglog rules, and the analysis can
take them from the rules instead of from its matchers. That is off by default.

Everything here was written for and checked with egglog 3.0.0.

## Files

| File | What it is |
|---|---|
| `rules.egg` | The terms, the reordering rules (commutativity, associativity, constant folding) and the idiom rules. Each match is saved as a row `Intent v n d c`: the value `v` computes `ceil(n / d)` where condition `c` holds (0 means always). |
| `cases.egg` | 46 hand-written expressions with the expected result of each. A case marked "Java:" was also run through the Java matcher. |
| `compare.egg`, `run_facts.py` | Compare the rules with the Java matchers on the expressions of real code. |
| `phase1.egg` | The rows the Java analysis reads when it takes its idioms from the rules. |
| `phase1-cases.egg` | Hand-written checks for `phase1.egg`. |
| `examples/Sum.sol` | Six small functions; the verdict of three changes when the rules decide. |

## Check the rules

```
cd roundAbout/egglog
egglog cases.egg
egglog phase1-cases.egg
```

Both must exit with status 0. A wrong expectation makes egglog stop with
"Check failed".

## Compare the rules with the Java matchers

```
EXPORT_EGGLOG=/tmp/facts mvn -B -o test
python3 roundAbout/egglog/run_facts.py /tmp/facts
```

With `EXPORT_EGGLOG` set, the analysis writes one facts file per analyzed
function: the arithmetic behind every division, and what the Java matchers
decided for it. `run_facts.py` runs the rules on each file and classifies each
expression: `same` (both read the same round-up), `differ`, `egglog-only`,
`today-only`, `literal` (neither) or `exact` (division by 1). Count sites, not
files: the number of files varies by one or two between runs.

## Let the rules decide

```
EGGLOG_PHASE1=$HOME/.cargo/bin/egglog EGGLOG_PHASE1_LOG=/tmp/phase1.log mvn -B -o test
```

or, on a `java` command line, `-DegglogPhase1=<egglog binary>`, with
`-DegglogRules=<this directory>` when not run from the repository root and
`-DegglogPhase1Log=<file>` for the log.

With the switch on, `RoundingRecognition` does not call its own bias, indicator
and masked matchers. For each function it runs egglog on that function's facts
and builds Q from the rows (`EgglogPhase1`). The guarded form
`(N - 1) / D + 1` and the branch form depend on control flow and stay in Java.
The log has one line per function: its name, the number of candidate
expressions, how many the rules read as a round-up, the milliseconds egglog
took, and `ok` or the failure. If egglog fails for a function, the Java
matchers decide that function and the log says `FAILED`.

## Where the rules and the Java matchers differ

Each line was run both ways.

| Expression | Java matchers | Rules |
|---|---|---|
| `(x + d - 1 + y) / d` | plain division | round-up of `(x + y) / d` |
| `(n + a * b - 1) / (b * a)` | plain division | round-up of `n / (a * b)` |
| `(x + d * 2 + 2) / (2 * d + 3)` | plain division | round-up of `x / (2 * d + 3)` |
| `(x + 5 + d - 1) / d` | plain division | round-up of `(x + 5) / d` |
| `(x - y + d) / (d + 1)` | plain division | round-up of `(x - y) / (d + 1)` |
| `(x + d + 2e19 - 1553255926290448385) / d` | round-up of `x / d`, which is wrong | plain division |

In the last line the constants sum to 2^64 - 1. The Java matcher keeps only the
low 64 bits of a constant written in scientific notation, and those of the sum
are -1.

The rules never read `(x + d - 1 - y) / d` as a round-up of `(x - y) / d`:
`x - y` reverts where the code does not (x = 0, y = 1, d = 5), so Q would not
be defined wherever the code is. A subtraction may appear in `n` only if the
code itself computes it on the way to the division (`Safe` in `rules.egg`).

## The example

`examples/Sum.sol`, analyzed with the switch off and on:

| Function | Expression | Off | On |
|---|---|---|---|
| `plain` | `(n + d - 1) / d` | Up | Up |
| `sumExact` | `(x + d - 1 + y) / d` | Down | Up |
| `sumProduct` | `(a * b + d - 1 + y) / d` | Down | Up |
| `sumDown` | `(x / k + d - 1 + y) / d` | Down | Inconsistent |
| `sumReused` | `t = x + d - 1 + y; t / d + t` | Down | Down |
| `sumThree` | `(x + d - 1 + y + z) / d` | Down | Down |

In `sumExact` the numerator `x + y` is a sum the code never computes as one
value. Q gives it the value number of the dividend, which is possible only when
nothing but the division reads the dividend (`sumReused`). Only a sum of two
values is handled (`sumThree`). In `sumDown` one addend is a quotient that
rounds down, so the round-up of the sum can err either way.

To reproduce (the conf names `solc8.30`; use the solc you have):

```
cd roundAbout/egglog/examples
certoraRun Sum.conf --dump_asts --build_only --disable_local_typechecking \
    --ignore_solidity_warnings --disable_internal_function_instrumentation
cd ../../..
CP=target/classes:target/roundabout-0.0.1-SNAPSHOT.jar
AST=roundAbout/egglog/examples/.certora_internal/latest/.asts.json
java -cp $CP com.certora.roundAbout.RoundAbout roundAbout/egglog/examples/Sum.conf off.json --combined $AST
java -DegglogPhase1=$HOME/.cargo/bin/egglog -cp $CP com.certora.roundAbout.RoundAbout roundAbout/egglog/examples/Sum.conf on.json --combined $AST
```

The `return` field of each function in `off.json` and `on.json` is the verdict.

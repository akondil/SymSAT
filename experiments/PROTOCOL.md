# Local development study protocol — 21 October 2026

This protocol is written before running the new study. The frozen cases.json
contains 34 synthetic development cases, including examples related to the
original paper. It is not a held-out, externally sourced or industrial benchmark.
All symbols are real; every division denominator is a positive constant.

Engines: current bounded SymSAT with AST_SIZE, the project's local SimplifyVisitor,
and installed SymPy simplify. The local greedy baseline is not Mathematica/SymPy
and does not use an identical rewrite set to SymSAT. These observations must not
be described as an isolated comparison of rewrite scheduling. Different systems'
resource units are not equated. SymSAT uses its default SearchLimits; the local
baseline uses its historical 20-pass bound. Java requests have a 15-second outer
deadline with worker process-group cleanup. This is a development measurement,
not an equal-resource comparative performance claim.

Default: two warmups and seven measured repetitions per case/engine. Java uses
one persistent JVM, excluding process startup and input parsing from solve time.
SymPy inputs are parsed before timing simplify. Its existing caches and the JVM's
JIT state are retained. Engine order rotates by case; repetition order is seeded.
Correctness checks, output parsing and statistics are outside solve timing.
No speedup hypothesis or significance threshold is asserted.

Save each measured observation, errors/timeouts, search stop reasons, per-case
median/quartiles, environment versions and source/corpus hashes. Report incomplete
searches, not just successful minimum-leaf examples. Do not overwrite old runs.
Common textual AST node counts are calculated by one Python syntax counter for
every serialized output; internal CAS node representations are not equated.

Check each output's equality with its original using SymPy under the declared
domain. SymPy self-checks are not independent validation of SymPy. Its role as an
external oracle for the Java engines is evidence, not a formal proof or a
floating-point error analysis. Adversarial/undefined cases stay in Java semantic
tests, outside this real-polynomial/trigonometric timing corpus.

All errors must remain in raw output. New examples or tuning after examining
results require a new corpus/protocol version; do not silently relabel this run
as evaluation on unseen data. Publication still needs stronger external tasks,
appropriately matched controls and a supported novelty claim.

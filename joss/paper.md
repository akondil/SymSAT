---
title: 'SymSAT: A budgeted equality-saturation DSL for symbolic simplification'
tags:
  - symbolic computation
  - computer algebra
  - e-graphs
  - term rewriting
  - code generation
  - java
authors:
  - name: Adil Kondiloğlu
    orcid: 0000-0002-2729-7532
    affiliation: 1
  - name: Hüseyin Pehlivan
    orcid: 0000-0002-0672-9009
    affiliation: 1
affiliations:
  - name: Karadeniz Technical University, Department of Computer Engineering, Trabzon, Türkiye
    index: 1
date: 21 October 2026
bibliography: paper.bib
---

# Summary

SymSAT is a domain-specific language (DSL) and engine for symbolic simplification built
on budgeted equality saturation. It combines declarative user-defined rewrite rules,
exact arbitrary-precision rational arithmetic, analytical differentiation, an
equivalence query, and numerical code generation to Java and C. Every search is bounded
by explicit node, iteration, work, match and integer-bit limits, so searches terminate
and return the best representation found within the budget. Built-in cancellation rules
are guarded by a conservative definedness and nonzero analysis. The project ships 829
automated checks covering semantics, definedness, assumptions, code generation, cost
bounds, resource limits and the command-line interface.

# Statement of need

Equality saturation [@tate2009equality; @willsey2021egg] avoids the phase-ordering and
termination problems of greedy term rewriting, but it is usually exposed as a library
for compiler developers (for example, the Rust egg library) rather than as a standalone,
interactive tool for symbolic mathematics. General computer algebra systems such as
SymPy [@meurer2017sympy] and Mathematica [@wolfram2003mathematica] instead rely on
layered greedy passes with large rule bases.

SymSAT fills a practical gap: it is a small, auditable, single-process DSL in which the
equality-saturation engine is the evaluation semantic itself, usable from the command
line with no Rust or Python host program. Because its search is deterministic and
budgeted, and because its correctness boundaries (axiom-relative `prove`, no
global-optimality claim, no IEEE 754 equivalence guarantee) are stated explicitly,
SymSAT is suited to teaching equality saturation and to prototyping soundness-bounded
symbolic simplification and code generation.

# Functionality

The language provides `rule`, `let`, `simplify`, `diff ... wrt ...`, `prove`,
`codegen ... as java|c`, `eval ... where`, `assume <symbol> != 0;`, and `print`. For
example:

    rule trig: cos(u)^2 + sin(u)^2 => 1;
    prove (sin(x)^2 + cos(x)^2 + 4) == 5;
    let f = (x * 2) / 2 + sin(x)^2 + cos(x)^2;
    simplify f;
    diff f wrt x;
    codegen f as java compute(x);

The engine performs congruence closure [@nelson1980fast; @downey1980variations; @nieuwenhuis2005proof],
commutative canonicalization, in-graph constant folding, and dynamic-programming
extraction over a cost metric. A seven-example demonstration reproduces 80.0%--92.3% AST
node reduction relative to the input against the project's local greedy baseline.

# Acknowledgements

The authors acknowledge the time and effort of the reviewers.

# SymSAT

A budgeted equality-saturation DSL for symbolic simplification with exact rational arithmetic.

SymSAT is a domain-specific language and engine for symbolic computation. It combines
declarative user rewrite rules, a bounded equality-saturation (e-graph) engine, exact
arbitrary-precision rational arithmetic, analytical differentiation, an equivalence query,
and numerical code generation to Java and C.

## Requirements

- JDK 21. The test suite needs no C compiler.

## Quick start

```bash
bash run.sh 'simplify sin(x)^2 + cos(x)^2 + 4;'
bash run.sh --file prog.txt
bash test.sh          # 829 checks
bash benchmark.sh     # 7-example demonstration (single-run, not a performance study)
```

## Language

```text
rule trig: cos(u)^2 + sin(u)^2 => 1;
prove (sin(x)^2 + cos(x)^2 + 4) == 5;
prove (x * (y + z)) == (x * y + x * z);
let f = (x * 2) / 2 + sin(x)^2 + cos(x)^2;
simplify f;
diff f wrt x;
codegen f as java compute(x);
eval (1/3 + 1/6);
assume x != 0;
simplify x/x;
```

## Repository layout

- `src/lang/` — Java sources (parser, e-graph engine, differentiation, code generation).
- `test.sh`, `run.sh`, `benchmark.sh` — build and run scripts.
- `examples/` — sample programs.
- `experiments/` — reproducible local study (`run_study.py`, frozen corpus, protocol).

## Correctness boundaries

- `prove` checks derivability from the active user rules, which are trusted axioms; it is
  not an independent mathematical correctness certificate.
- Search is bounded by deterministic limits (nodes, iterations, work, matches, integer bits),
  not wall-clock time; it does not guarantee global optimality among all equivalent forms.
- Generated code converts exact rational constants to binary64 with round-to-nearest and does
  not guarantee IEEE 754 equivalence between intermediate operations.

## Testing

`bash test.sh` runs 829 checks across semantic, domain, assumption, code-generation,
simplification, cost-lower-bound, resource-limit, language/CLI, and Java CSE/constant
conversion groups.

## Citing

If you use SymSAT in research, please cite it via `CITATION.cff` or:

Kondiloğlu, A., & Pehlivan, H. (2026). SymSAT: A budgeted equality-saturation DSL for
symbolic simplification (v1.0). Zenodo. https://doi.org/10.5281/zenodo.23536493

## License

MIT — see `LICENSE.txt`.

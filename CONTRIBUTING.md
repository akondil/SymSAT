# Contributing to SymSAT

Thanks for your interest in contributing.

## Reporting issues

Please open a GitHub issue and include the SymSAT version (or commit), your JDK version
(`java -version`), the exact program or expression, and the full error output.

## Development setup

Requirements: JDK 21. The test suite does not need a C compiler.

```bash
bash test.sh          # compile and run all 829 checks
bash run.sh 'simplify sin(x)^2 + cos(x)^2 + 4;'
```

## Pull requests

- Keep changes focused and add or update tests in `src/lang/*RegressionTest.java`.
- Run `bash test.sh` before submitting; it must pass.
- If you change `src/lang/Grammar.jj`, regenerate the parser with
  `bash regenerate-parser.sh` and commit the generated files together.

## Code of conduct

Please read `CODE_OF_CONDUCT.md`.

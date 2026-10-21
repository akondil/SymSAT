#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"${JAVAC:-javac}" -d "$DIR/bin" "$DIR"/src/lang/*.java
"${JAVA:-java}" -cp "$DIR/bin" lang.BenchmarkSuite "$@"

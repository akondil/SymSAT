#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PARSER_JAR="${JAVACC_JAR:-$HOME/.m2/repository/net/java/dev/javacc/javacc/5.0/javacc-5.0.jar}"
if [[ ! -f "$PARSER_JAR" ]]; then
    echo "JavaCC 5.0 jar not found; set JAVACC_JAR to its local path." >&2
    exit 1
fi
"${JAVA:-java}" -cp "$PARSER_JAR" javacc "-OUTPUT_DIRECTORY=$DIR/src/lang" "$DIR/src/lang/Grammar.jj"

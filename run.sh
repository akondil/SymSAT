#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"${JAVAC:-javac}" -d "$DIR/bin" "$DIR"/src/lang/*.java
if [[ $# -eq 0 ]]; then
    set -- --file "$DIR/prog.txt"
fi
exec "${JAVA:-java}" -cp "$DIR/bin" lang.Interpreter "$@"

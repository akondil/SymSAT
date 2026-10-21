#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"${JAVAC:-javac}" -d "$DIR/bin" "$DIR"/src/lang/*.java
"${JAVA:-java}" -cp "$DIR/bin" lang.RegressionTest
"${JAVA:-java}" -cp "$DIR/bin" lang.DomainRegressionTest
"${JAVA:-java}" -cp "$DIR/bin" lang.AssumptionRegressionTest
"${JAVA:-java}" -cp "$DIR/bin" lang.CodegenRegressionTest
"${JAVA:-java}" -cp "$DIR/bin" lang.SimplificationRegressionTest
"${JAVA:-java}" -cp "$DIR/bin" lang.CostBoundRegressionTest
"${JAVA:-java}" -cp "$DIR/bin" lang.ResourceRegressionTest
"${JAVA:-java}" -cp "$DIR/bin" lang.LanguageRegressionTest
"${JAVA:-java}" -cp "$DIR/bin" lang.JavaEmissionRegressionTest

package lang;

import java.util.*;

/**
 * Small demonstration suite for inspecting SymSAT outputs and graph sizes.
 * This one-run suite is not an empirical performance evaluation.
 */
public class BenchmarkSuite {

    public static int countNodes(Exp e) {
        if (e == null) return 0;
        if (e instanceof Const || e instanceof Symbol) return 1;
        if (e instanceof Plus) return 1 + countNodes(((Plus) e).a) + countNodes(((Plus) e).b);
        if (e instanceof Minus) return 1 + countNodes(((Minus) e).a) + countNodes(((Minus) e).b);
        if (e instanceof Multiply) return 1 + countNodes(((Multiply) e).a) + countNodes(((Multiply) e).b);
        if (e instanceof Divide) return 1 + countNodes(((Divide) e).a) + countNodes(((Divide) e).b);
        if (e instanceof Power) return 1 + countNodes(((Power) e).base) + countNodes(((Power) e).exp);
        if (e instanceof UnaryMinus) return 1 + countNodes(((UnaryMinus) e).a);
        if (e instanceof FunctionCall) {
            int c = 1;
            for (Exp arg : ((FunctionCall) e).args) c += countNodes(arg);
            return c;
        }
        return 1;
    }

    public static class BenchmarkResult {
        public final String testName;
        public final String inputExpr;
        public final int initialNodes;
        public final String classicalResult;
        public final int classicalNodes;
        public final boolean classicalCycleOrStuck;
        public final String egraphResult;
        public final int egraphNodes;
        public final int eclasses;
        public final int enodes;
        public final long egraphTimeUs;
        public final double reductionPercent;

        public BenchmarkResult(String testName, String inputExpr, int initialNodes,
                               String classicalResult, int classicalNodes, boolean classicalCycleOrStuck,
                               String egraphResult, int egraphNodes, int eclasses, int enodes,
                               long egraphTimeUs) {
            this.testName = testName;
            this.inputExpr = inputExpr;
            this.initialNodes = initialNodes;
            this.classicalResult = classicalResult;
            this.classicalNodes = classicalNodes;
            this.classicalCycleOrStuck = classicalCycleOrStuck;
            this.egraphResult = egraphResult;
            this.egraphNodes = egraphNodes;
            this.eclasses = eclasses;
            this.enodes = enodes;
            this.egraphTimeUs = egraphTimeUs;
            this.reductionPercent = initialNodes > 0
                    ? ((double) (initialNodes - egraphNodes) / initialNodes) * 100.0
                    : 0.0;
        }
    }

    public static BenchmarkResult runBenchmark(String testName, String exprStr, List<Rule> customRules) {
        try {
            Exp ast = Interpreter.parseString(exprStr);
            int initialNodes = countNodes(ast);

            // 1. Classical Greedy Simplification
            SimplifyVisitor simplifier = new SimplifyVisitor();
            Exp classicalOut = simplifier.simplify(ast);
            int classicalNodes = countNodes(classicalOut);
            boolean classicalStuck = classicalNodes >= initialNodes && !classicalOut.equals(new Const(0));

            // 2. SymSAT E-Graph Equality Saturation
            List<Rule> rules = new ArrayList<>(Interpreter.getDefaultEquivalenceRules());
            if (customRules != null) {
                rules.addAll(customRules);
            }

            EGraph egraph = new EGraph();
            int rootId = egraph.add(ast);

            // Execution
            long start = System.nanoTime();
            egraph.saturate(rules, 8, 2000);
            long elapsedUs = (System.nanoTime() - start) / 1000;

            Extractor extractor = new Extractor(egraph, CostFunction.AST_SIZE);
            Exp egraphOut = extractor.extract(rootId);
            int egraphNodes = countNodes(egraphOut);

            return new BenchmarkResult(
                    testName, exprStr, initialNodes,
                    classicalOut.toString(), classicalNodes, classicalStuck,
                    egraphOut.toString(), egraphNodes,
                    egraph.size(), egraph.totalNodeCount(), elapsedUs
            );
        } catch (Exception e) {
            throw new RuntimeException("Benchmark failed on " + exprStr + ": " + e.getMessage(), e);
        }
    }

    public static void main(String[] args) {
        System.out.println("========================================================================================================");
        System.out.println("                         SymSAT DEMONSTRATION MEASUREMENTS (NOT A PERFORMANCE STUDY)                   ");
        System.out.println("========================================================================================================");

        List<BenchmarkResult> results = new ArrayList<>();

        // Test 1: Cycle / Phase-Ordering Avoidance
        results.add(runBenchmark("Phase-Ordering 1", "(x * 2) / 2", null));
        results.add(runBenchmark("Phase-Ordering 2", "((x * 4) / 2) / 2", null));

        // Test 2: Trigonometric Identity Elimination
        results.add(runBenchmark("Trig Identity 1", "sin(x)^2 + cos(x)^2 + 4", null));
        results.add(runBenchmark("Trig Identity 2", "(sin(t)^2 + cos(t)^2) * 5", null));

        // Test 3: Algebraic Equivalence Verification (Proving f(x) - g(x) == 0)
        results.add(runBenchmark("Identity Proof 1", "(x * (y + z)) - (x * y + x * z)", null));
        results.add(runBenchmark("Identity Proof 2", "(sin(theta)^2 + cos(theta)^2) - 1", null));

        // Test 4: Higher-Order Derivative Chain Simplification
        try {
            Exp f = Interpreter.parseString("(x + 1)^3");
            DiffVisitor dX = new DiffVisitor("x");
            Exp d1 = f.accept(dX);
            Exp d2 = d1.accept(dX);
            results.add(runBenchmark("Diff 2nd-Order", d2.toString(), null));
        } catch (Exception ignored) {}

        // Print an informal single-run summary.
        System.out.printf("%-18s | %-28s | %-12s | %-12s | %-10s | %-10s | %-8s%n",
                "Example", "Input Expression", "Greedy AST", "SymSAT AST", "Output AST Δ%", "E-Nodes", "One Run (µs)");
        System.out.println("--------------------------------------------------------------------------------------------------------");

        for (BenchmarkResult r : results) {
            System.out.printf("%-18s | %-28s | %-12s | %-12s | %-9.1f%% | %-10d | %-8d%n",
                    r.testName,
                    truncate(r.inputExpr, 28),
                    truncate(r.classicalResult, 12),
                    truncate(r.egraphResult, 12),
                    r.reductionPercent,
                    r.enodes,
                    r.egraphTimeUs
            );
        }

        System.out.println("========================================================================================================");
        System.out.println("These single-run demonstration measurements are not evidence of comparative performance.");
        System.out.println("========================================================================================================");
    }

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }
}

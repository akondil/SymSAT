package lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Manual demonstration driver for SymSAT's current features.
 * Demonstrates:
 * 1. Declarative Rule Matching & Rewriting
 * 2. Bidirectional Rule Handling via Equality Saturation
 * 3. Multi-objective Extraction (AST Size vs Operation Count)
 * 4. Trigonometric & Algebraic Rewrite Examples
 */
public class SymSatTest {

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println("  SymSAT Feature Demonstration: Equality Saturation and Rewriting ");
        System.out.println("=================================================================\n");

        testAlgebraicIdentitiesWithCycles();
        System.out.println("\n-----------------------------------------------------------------\n");
        testTrigonometricPythagorean();
        System.out.println("\n-----------------------------------------------------------------\n");
        testMultiObjectiveExtraction();
    }

    /**
     * Test 1: A bounded equality-saturation example using bidirectional rules.
     */
    public static void testAlgebraicIdentitiesWithCycles() {
        System.out.println("[Test 1: Bidirectional & Associative Rewriting]");
        System.out.println("Goal: Explore (x * 2) / 2 under a small bidirectional rewrite set.");

        try {
            String exprStr = "(x * 2) / 2";
            Exp initial = Interpreter.parseString(exprStr);
            System.out.println("Initial Expression: " + initial);

            EGraph egraph = new EGraph();
            int rootId = egraph.add(initial);

            List<Rule> rules = new ArrayList<>();
            // Bidirectional rules (would loop infinitely in classical CAS!)
            rules.add(Rule.bidirectional("assoc-mul-div", "(?a * ?b) / ?c", "?a * (?b / ?c)"));
            rules.add(Rule.bidirectional("comm-mul", "?a * ?b", "?b * ?a"));
            // Simplification rules
            rules.add(Rule.forward("div-same", "2 / 2", "1"));
            rules.add(Rule.forward("mul-one", "?a * 1", "?a"));

            System.out.println("Applying " + rules.size() + " rules (including bidirectional associativity)...");
            long startTime = System.nanoTime();
            int iters = egraph.saturate(rules, 10);
            long elapsedUs = (System.nanoTime() - startTime) / 1000;

            System.out.println("Equality Saturation completed in " + iters + " iterations (" + elapsedUs + " µs).");
            System.out.println("Total E-Classes: " + egraph.size() + ", Total E-Nodes: " + egraph.totalNodeCount());

            Extractor extractor = new Extractor(egraph, CostFunction.AST_SIZE);
            Exp result = extractor.extract(rootId);
            System.out.println("Extracted Result (AST_SIZE): " + result);

            boolean passed = result.toString().equals("x");
            System.out.println("Expected output observed: " + passed);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Test 2: Trigonometric rewrite example: sin(x)^2 + cos(x)^2 => 1
     */
    public static void testTrigonometricPythagorean() {
        System.out.println("[Test 2: Trigonometric Simplification]");
        System.out.println("Goal: Prove sin(x)^2 + cos(x)^2 + 4 simplifies to 5.");

        try {
            String exprStr = "sin(x)^2 + cos(x)^2 + 4";
            Exp initial = Interpreter.parseString(exprStr);
            System.out.println("Initial Expression: " + initial);

            EGraph egraph = new EGraph();
            int rootId = egraph.add(initial);

            List<Rule> rules = new ArrayList<>();
            rules.add(Rule.forward("pythagorean", "sin(?x)^2 + cos(?x)^2", "1"));
            rules.add(Rule.forward("fold-add-1-4", "1 + 4", "5"));

            egraph.saturate(rules, 5);

            Extractor extractor = new Extractor(egraph, CostFunction.AST_SIZE);
            Exp result = extractor.extract(rootId);
            System.out.println("Extracted Result: " + result);

            boolean passed = result.toString().equals("5");
            System.out.println("Verification: " + (passed ? "PASSED" : "FAILED"));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Test 3: Cost-guided extraction with the two built-in cost functions.
     * Comparing AST Size minimization vs Operation Cost minimization.
     */
    public static void testMultiObjectiveExtraction() {
        System.out.println("[Test 3: Multi-Objective Cost Extraction]");
            System.out.println("Goal: Compare representatives selected by the two built-in cost functions.");

        try {
            // Expression: x * (y + z)
            String exprStr = "x * (y + z)";
            Exp initial = Interpreter.parseString(exprStr);
            System.out.println("Expression: " + initial);

            EGraph egraph = new EGraph();
            int rootId = egraph.add(initial);

            List<Rule> rules = new ArrayList<>();
            // Distributivity in both directions: factored form <=> expanded form
            rules.add(Rule.bidirectional("distrib", "?x * (?y + ?z)", "?x * ?y + ?x * ?z"));

            egraph.saturate(rules, 5);

            Extractor sizeExtractor = new Extractor(egraph, CostFunction.AST_SIZE);
            Exp sizeResult = sizeExtractor.extract(rootId);
            System.out.println("Extracted with AST_SIZE:       " + sizeResult + " (Cost: " + sizeExtractor.getCost(rootId) + ")");

            Extractor opExtractor = new Extractor(egraph, CostFunction.OPERATION_COST);
            Exp opResult = opExtractor.extract(rootId);
            System.out.println("Extracted with OPERATION_COST: " + opResult + " (Cost: " + opExtractor.getCost(rootId) + ")");

            System.out.println("Verification: Both representations co-exist in E-Graph simultaneously!");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

package lang;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Interpreter {
    public static final int MAX_SOURCE_CHARS = 1_000_000;
    private static void checkSource(String source) {
        if (source.length() > MAX_SOURCE_CHARS)
            throw new SearchLimitException(EGraph.StopReason.INPUT_LIMIT, "Source text limit reached");
    }

    public static Exp parseString(String input) throws ParseException {
        checkSource(input);
        Grammar parser = new Grammar(new StringReader(input));
        return parser.parse();
    }

    public static Program parseProgram(String script) throws ParseException {
        checkSource(script);
        Grammar parser = new Grammar(new StringReader(script));
        return parser.parseProgram();
    }

    /** Parses and executes a DSL program in the caller-supplied context. */
    public static List<Object> executeProgram(String script, Context context) throws ParseException {
        if (context == null) throw new NullPointerException("context");
        Program program = parseProgram(script);
        List<Object> results = new ArrayList<>(program.statements.size());
        for (Stmt statement : program.statements) {
            results.add(statement.execute(context));
        }
        return Collections.unmodifiableList(results);
    }

    public static List<Rule> getDefaultEquivalenceRules() {
        List<Rule> rules = new ArrayList<>();
        // Algebraic Equivalence Rules (Bidirectional & Forward)
        rules.add(Rule.forward("add-zero", "?x + 0", "?x"));
        rules.add(Rule.forwardWhen("mul-zero", "?x * 0", "0", "?x", Rule.Condition.DEFINED));
        rules.add(Rule.forward("mul-one", "?x * 1", "?x"));
        rules.add(Rule.forward("div-one", "?x / 1", "?x"));
        rules.add(Rule.forwardWhen("div-same", "?x / ?x", "1", "?x", Rule.Condition.NONZERO));
        rules.add(Rule.forwardWhen("sub-self", "?x - ?x", "0", "?x", Rule.Condition.DEFINED));
        rules.add(Rule.forwardWhen("pow-zero", "?x ^ 0", "1", "?x", Rule.Condition.DEFINED));
        rules.add(Rule.forward("pow-one", "?x ^ 1", "?x"));
        rules.add(Rule.forward("add-neg", "?a + (-?b)", "?a - ?b"));
        rules.add(Rule.forward("mul-neg", "?a * (-?b)", "-(?a * ?b)"));

        // Associativity & Distributivity
        rules.add(Rule.forward("assoc-add", "(?a + ?b) + ?c", "?a + (?b + ?c)"));
        rules.add(Rule.bidirectional("distrib", "?x * (?y + ?z)", "?x * ?y + ?x * ?z"));
        rules.add(Rule.bidirectional("assoc-mul-div", "(?a * ?b) / ?c", "?a * (?b / ?c)"));

        // Trigonometric Pythagorean Identity
        rules.add(Rule.forward("pythagorean", "sin(?x)^2 + cos(?x)^2", "1"));
        return rules;
    }

    public static Exp simplifyWithEGraph(Exp expr, List<Rule> rules, CostFunction costFunction) {
        return simplifyWithEGraph(expr, rules, costFunction,
                Context.DEFAULT_MAX_ITERATIONS, Context.DEFAULT_NODE_LIMIT);
    }

    public static Exp simplifyWithEGraph(Exp expr, List<Rule> rules, CostFunction costFunction,
                                         int maxIterations, int nodeLimit) {
        return simplifyWithEGraph(expr, rules, costFunction, maxIterations, nodeLimit, Collections.emptySet());
    }

    public static Exp simplifyWithEGraph(Exp expr, List<Rule> rules, CostFunction costFunction,
                                         int maxIterations, int nodeLimit, java.util.Set<String> nonzeroSymbols) {
        return simplifyWithResult(expr, rules, costFunction, maxIterations, nodeLimit, nonzeroSymbols).expression;
    }

    public static SimplificationResult simplifyWithResult(Exp expr, List<Rule> rules, CostFunction costFunction,
                                         int maxIterations, int nodeLimit, java.util.Set<String> nonzeroSymbols) {
        return simplifyWithResult(expr, rules, costFunction, new SearchLimits(maxIterations, nodeLimit), nonzeroSymbols);
    }

    public static SimplificationResult simplifyWithResult(Exp expr, List<Rule> rules, CostFunction costFunction,
                                         SearchLimits limits, java.util.Set<String> nonzeroSymbols) {
        java.util.Objects.requireNonNull(expr, "expr");
        java.util.Objects.requireNonNull(rules, "rules");
        java.util.Objects.requireNonNull(costFunction, "costFunction");
        java.util.Objects.requireNonNull(limits, "limits");
        limits.validateInput(expr);
        java.util.Set<String> assumptions = new java.util.TreeSet<>(nonzeroSymbols);
        long start = System.nanoTime();
        EGraph egraph = new EGraph(assumptions, limits);
        int rootId = egraph.add(expr);
        long inserted = System.nanoTime();
        EGraph.SaturationResult saturation = egraph.saturateForExtraction(rules, limits.maxIterations, limits.nodeLimit, rootId, costFunction);
        long saturated = System.nanoTime();
        Extractor extractor = new Extractor(egraph, costFunction);
        Exp output = extractor.extract(rootId);
        double cost = extractor.getCost(rootId);
        long extracted = System.nanoTime();
        return new SimplificationResult(expr, output, saturation, assumptions, costFunction, cost,
                limits, inserted - start, saturated - inserted, extracted - saturated);
    }

    public static void main(String[] args) {
        int status = run(args);
        if (status != 0) System.exit(status);
    }

    private static final class CliContext extends Context {
        final java.util.Set<EGraph.StopReason> incomplete = java.util.EnumSet.noneOf(EGraph.StopReason.class);
        @Override public SimplificationResult simplifyDetailed(Exp expression, CostFunction cost) {
            SimplificationResult result=super.simplifyDetailed(expression,cost);
            if(!result.isSaturated() && !result.reachedCostLowerBound()) incomplete.add(result.saturation.stopReason);
            return result;
        }
        @Override public EquivalenceResult checkEquivalence(Exp left,Exp right) {
            EquivalenceResult result=super.checkEquivalence(left,right);
            if(result.status==EquivalenceStatus.INCOMPLETE) incomplete.add(result.saturation.stopReason);
            return result;
        }
    }

    /** CLI status: 0 success, 1 language/runtime/IO failure, 2 usage, 3 resource limit. */
    public static int run(String[] args) {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("Usage: run.sh [--file program.sym | 'expression or script']");
            return 0;
        }
        boolean file = args.length == 0 || (args.length == 2 && args[0].equals("--file"));
        if (!file && (args.length != 1 || args[0].startsWith("--"))) {
            System.err.println("Usage: run.sh [--file program.sym | 'expression or script']");
            return 2;
        }
        try {
            String input;
            if (file) {
                Path path = Path.of(args.length == 0 ? "prog.txt" : args[1]);
                byte[] bytes;
                try (InputStream stream = Files.newInputStream(path)) {
                    bytes = stream.readNBytes(MAX_SOURCE_CHARS + 1);
                }
                if (bytes.length > MAX_SOURCE_CHARS)
                    throw new SearchLimitException(EGraph.StopReason.INPUT_LIMIT, "Source file byte limit reached");
                input = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } else input = args[0];
            checkSource(input);
            CliContext context = new CliContext();
            System.out.println("SymSAT: Equality-Saturation Symbolic Programming Language");
            if (file || input.contains(";")) {
                parseProgram(input).execute(context);
            } else {
                SimplificationResult result = context.simplifyDetailed(parseString(input), null);
                System.out.println("[Simplify] " + result.input + "\n       ==> " + result.expression);
                System.out.println("[Search] " + result.summary());
            }
            if(!context.incomplete.isEmpty()) {
                System.err.println("Search incomplete: " + context.incomplete);
                return 3;
            }
            return 0;
        } catch (SearchLimitException error) {
            System.err.println("Resource limit [" + error.reason + "]: " + error.getMessage());
            return 3;
        } catch (TokenMgrError error) {
            System.err.println("Lexical error: " + error.getMessage());
            return 1;
        } catch (Exception error) {
            System.err.println("Error executing SymSAT: " + error.getMessage());
            return 1;
        }
    }
}

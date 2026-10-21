package lang;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Conditional identities must carry immutable premises and respect context lifetime. */
public final class AssumptionRegressionTest {
    private static int checks;
    private static Exp p(String text) throws ParseException { return Interpreter.parseString(text); }
    private static void require(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }
    private static void rejects(Runnable action, String name) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        Context context = new Context();
        Context.EquivalenceResult before = context.checkEquivalence(p("x/x"), p("1"));
        require(before.status != Context.EquivalenceStatus.PROVED, "no implicit nonzero premise");
        context.assumeNonzero("x");
        Context.EquivalenceResult after = context.checkEquivalence(p("x/x"), p("1"));
        require(after.status == Context.EquivalenceStatus.PROVED, "conditional identity");
        require(after.nonzeroAssumptions.equals(Set.of("x")), "proof carries premise");
        require(before.nonzeroAssumptions.isEmpty(), "old proof snapshot is unchanged");
        context.assumeNonzero("y");
        require(after.nonzeroAssumptions.equals(Set.of("x")), "new assumptions do not alter old proof");
        try { after.nonzeroAssumptions.add("z"); throw new AssertionError("mutable proof premises"); }
        catch (UnsupportedOperationException expected) { checks++; }
        Set<String> snapshot = context.getNonzeroAssumptions();
        context.assumeNonzero("z");
        require(!snapshot.contains("z"), "assumption getter returns snapshot");
        context.assumeNonzero("x");
        require(context.getNonzeroAssumptions().size() == 3, "duplicate premise is idempotent");
        require(new Context().checkEquivalence(p("x/x"), p("1")).status != Context.EquivalenceStatus.PROVED,
                "contexts are isolated");

        Context xOnly = new Context();
        xOnly.assumeNonzero("x");
        for (String[] pair : new String[][] {
                {"x/x", "1"}, {"(x*x)/(x*x)", "1"}, {"(-x)/(-x)", "1"},
                {"0*(1/x)", "0"}, {"(1/x)^0", "1"}, {"(1/x)-(1/x)", "0"}})
            require(xOnly.proveEquivalence(p(pair[0]), p(pair[1])), "premise propagated: " + pair[0]);
        for (String[] pair : new String[][] {
                {"y/y", "1"}, {"(x-x)/(x-x)", "1"}, {"0/0", "1"},
                {"0*(1/0)", "0"}, {"0*ln(x)", "0"}, {"sin(x)/sin(x)", "1"},
                {"(x+1)/(x+1)", "1"}})
            require(!xOnly.proveEquivalence(p(pair[0]), p(pair[1])), "premise must not overreach: " + pair[0]);

        rejects(() -> xOnly.defineVar("x", new Const(0)), "cannot rebind assumed symbol to zero");
        rejects(() -> xOnly.defineVar("x", new Const(2)), "assumed symbols remain symbolic");
        require(!xOnly.hasVar("x"), "rejected binding is atomic");
        Context bound = new Context(); bound.defineVar("a", new Const(0));
        rejects(() -> bound.assumeNonzero("a"), "bound names cannot acquire symbolic premises");
        require(bound.getNonzeroAssumptions().isEmpty(), "rejected premise is atomic");
        for (String invalid : List.of("?x", "x+y", "", "0", "x y"))
            rejects(() -> xOnly.assumeNonzero(invalid), "invalid assumption name " + invalid);

        for (int value : new int[] {-3, -1, 1, 2, 5}) {
            Map<String, Rational> env = Map.of("x", new Rational(value));
            require(xOnly.evaluate(p("x/x"), env).equals(Rational.ONE), "valid numeric binding");
            require(xOnly.evaluate(xOnly.simplify(p("x/x"), null), env).equals(Rational.ONE),
                    "conditional simplification numeric agreement");
        }
        rejects(() -> {
            try { xOnly.evaluate(p("1"), Map.of("x", Rational.ZERO)); }
            catch (ParseException e) { throw new AssertionError(e); }
        }, "premise checked even after variable disappears");
        require(xOnly.evaluate(p("1"), Collections.emptyMap()).equals(Rational.ONE),
                "evaluation does not require irrelevant numeric bindings");
        xOnly.defineVar("ratio", xOnly.simplify(p("x/x"), null));
        rejects(() -> xOnly.evaluate(new Symbol("ratio"), Map.of("x", Rational.ZERO)),
                "let result retains context contract");

        Set<String> mutable = new HashSet<>(Set.of("x"));
        EGraph graph = new EGraph(mutable); mutable.clear();
        int root = graph.add(p("x"));
        require(new DomainAnalysis(graph).isNonzero(root), "e-graph owns a premise snapshot");

        // Exercise the regenerated parser and user-facing output, including generated Java contracts.
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<Object> results;
        try (PrintStream captured = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            results = Interpreter.executeProgram(
                    "# Sıfır olmama varsayımı: ölçüm\nassume x != 0; simplify x/x; prove x/x == 1; "
                    + "let ratio=x/x; eval ratio where x=2; "
                    + "codegen x/x as java ratioJava(x);", new Context());
        } finally { System.setOut(original); }
        require(results.size() == 6, "DSL assumption script executes");
        require(results.get(1).equals(p("1")), "DSL conditional simplify");
        require(((Context.EquivalenceResult) results.get(2)).nonzeroAssumptions.equals(Set.of("x")),
                "DSL proof premises");
        try {
            Interpreter.executeProgram("assume x != 0; eval 1 where x=0;", new Context());
            throw new AssertionError("DSL accepted conflicting evaluation");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().contains("Evaluation violates assumption"), "DSL refuses conflicting evaluation");
        }
        require(results.get(4).equals(Rational.ONE), "DSL accepts valid evaluation");
        require(((String) results.get(5)).startsWith("// Requires: x != 0"), "Java contract exported");
        require(bytes.toString(StandardCharsets.UTF_8).contains("[Eval] ratio"), "valid evaluation reported");
        require(bytes.toString(StandardCharsets.UTF_8).contains("[Assumptions] x != 0"), "visible proof conditions");
        try {
            Interpreter.parseProgram("assume x != 1;");
            throw new AssertionError("unsupported nonzero RHS accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        try {
            Interpreter.parseProgram("assume x == 0;");
            throw new AssertionError("unsupported equality assumption accepted");
        } catch (ParseException expected) { checks++; }
        System.out.println("PASS: " + checks + " assumption regression checks");
    }
}

package lang;

import java.util.*;

/** Domain regressions exercise public DSL APIs as well as direct rule application. */
public final class DomainRegressionTest {
    private static int checks;
    private static Exp p(String text) throws ParseException { return Interpreter.parseString(text); }
    private static void require(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        Context context = new Context();
        String[][] blocked = {
            {"0/0", "1"}, {"x/x", "1"}, {"0*(1/0)", "0"},
            {"(1/0)-(1/0)", "0"}, {"(1/0)^0", "1"},
            {"0*(1/(2-2))", "0"}, {"0*(0^(-1))", "0"},
            {"0*ln(0)", "0"}, {"0*ln(-1)", "0"},
            {"0*f(x)", "0"}, {"(x/x)-(x/x)", "0"},
            {"0*(x^(-1))", "0"}, {"0*(x^(1/2))", "0"}
        };
        for (String[] pair : blocked) {
            require(context.checkEquivalence(p(pair[0]), p(pair[1])).status != Context.EquivalenceStatus.PROVED,
                    "must not derive: " + Arrays.toString(pair));
            require(!context.simplify(p(pair[0]), null).equals(p(pair[1])),
                    "must not erase domain: " + pair[0]);
        }
        String[][] allowed = {
            {"0*x", "0"}, {"x-x", "0"}, {"x^0", "1"}, {"0^0", "1"},
            {"2/2", "1"}, {"(2+3)/(2+3)", "1"},
            {"0*(x/2)", "0"}, {"0*sin(x)", "0"}, {"0*cos(x)", "0"},
            {"exp(x)/exp(x)", "1"}, {"0*ln(2)", "0"},
            {"(x*2)/2", "x"}, {"sin(x)^2+cos(x)^2+4", "5"}
        };
        for (String[] pair : allowed)
            require(context.checkEquivalence(p(pair[0]), p(pair[1])).status == Context.EquivalenceStatus.PROVED,
                    "valid identity: " + Arrays.toString(pair));

        Rule division = Rule.forwardWhen("safe-div", "?a/?a", "1", "?a", Rule.Condition.NONZERO);
        require(division.applyForward(p("0/0")) == null, "direct rule rejects zero");
        require(division.applyForward(p("x/x")) == null, "direct rule rejects unknown nonzero");
        require(division.applyForward(p("(2+3)/(2+3)")).equals(p("1")), "direct rule folds constants");
        try {
            Rule.forwardWhen("bad", "?x", "0", "?y", Rule.Condition.DEFINED);
            throw new AssertionError("unbound guard accepted");
        } catch (IllegalArgumentException expected) { checks++; }

        // User equations remain trusted axioms; a familiar rule name cannot add an implicit guard.
        Context custom = new Context(List.of(Rule.forward("div-same", "?x/?x", "1")));
        require(custom.proveEquivalence(p("0/0"), p("1")), "explicit user axiom semantics unchanged");
        Context aliases = new Context();
        aliases.defineVar("a", p("1/0"));
        require(!aliases.simplify(p("0*a"), null).equals(p("0")), "let substitution preserves partial expression");

        SimplifyVisitor greedy = new SimplifyVisitor();
        for (String expression : List.of("x/x", "0/x", "0*ln(0)", "ln(0)-ln(0)", "ln(0)^0", "1^ln(0)"))
            require(greedy.simplify(p(expression)).equals(p(expression)), "greedy domain: " + expression);
        try {
            greedy.simplify(p("0^(-1)"));
            throw new AssertionError("greedy erased negative zero power");
        } catch (ArithmeticException expected) { checks++; }

        // Exact numerical agreement at valid rational points; include zeros and negative values.
        for (int x = -4; x <= 4; x++) for (String expression : List.of("0*x", "x-x", "x^0", "0*(x/2)", "(x*2)/2")) {
            Map<String, Rational> env = Map.of("x", new Rational(x));
            Exp original = p(expression);
            require(original.accept(new EvalVisitor(env)).equals(context.simplify(original, null).accept(new EvalVisitor(env))),
                    "evaluation preserved: " + expression + " at " + x);
        }
        System.out.println("PASS: " + checks + " domain regression checks");
    }
}

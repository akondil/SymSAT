package lang;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pattern Matcher for Symbolic Expressions with support for pattern variables (?x, ?y, etc.)
 * and commutative operators (+, *).
 */
public class PatternMatcher {

    /**
     * Checks if the target expression matches the pattern.
     * If matched, returns the variable bindings; otherwise returns null.
     */
    public static Map<String, Exp> match(Exp pattern, Exp target) {
        Map<String, Exp> bindings = new HashMap<>();
        if (matchInternal(pattern, target, bindings)) {
            return bindings;
        }
        return null;
    }

    /**
     * Instantiates a template expression by replacing pattern variables with bound expressions.
     */
    public static Exp instantiate(Exp template, Map<String, Exp> bindings) {
        SubstVisitor subst = new SubstVisitor(bindings);
        return template.accept(subst);
    }

    private static boolean matchInternal(Exp pattern, Exp target, Map<String, Exp> bindings) {
        if (pattern == null || target == null) {
            return false;
        }

        // Case 1: Pattern is a pattern variable like ?x, ?u, ?v
        if (pattern instanceof Symbol && ((Symbol) pattern).isPatternVariable()) {
            String pName = ((Symbol) pattern).name;
            if (bindings.containsKey(pName)) {
                return bindings.get(pName).equals(target);
            } else {
                bindings.put(pName, target);
                return true;
            }
        }

        // Case 2: Constant
        if (pattern instanceof Const) {
            return pattern.equals(target);
        }

        // Case 3: Literal Symbol (e.g. x, pi)
        if (pattern instanceof Symbol) {
            return pattern.equals(target);
        }

        // Case 4: Plus (Commutative)
        if (pattern instanceof Plus) {
            if (!(target instanceof Plus)) return false;
            Plus p = (Plus) pattern;
            Plus t = (Plus) target;

            // Try direct match: p.a with t.a, p.b with t.b
            Map<String, Exp> copy1 = new HashMap<>(bindings);
            if (matchInternal(p.a, t.a, copy1) && matchInternal(p.b, t.b, copy1)) {
                bindings.clear();
                bindings.putAll(copy1);
                return true;
            }

            // Try commutative match: p.a with t.b, p.b with t.a
            Map<String, Exp> copy2 = new HashMap<>(bindings);
            if (matchInternal(p.a, t.b, copy2) && matchInternal(p.b, t.a, copy2)) {
                bindings.clear();
                bindings.putAll(copy2);
                return true;
            }

            return false;
        }

        // Case 5: Multiply (Commutative)
        if (pattern instanceof Multiply) {
            if (!(target instanceof Multiply)) return false;
            Multiply p = (Multiply) pattern;
            Multiply t = (Multiply) target;

            // Direct match
            Map<String, Exp> copy1 = new HashMap<>(bindings);
            if (matchInternal(p.a, t.a, copy1) && matchInternal(p.b, t.b, copy1)) {
                bindings.clear();
                bindings.putAll(copy1);
                return true;
            }

            // Commutative match
            Map<String, Exp> copy2 = new HashMap<>(bindings);
            if (matchInternal(p.a, t.b, copy2) && matchInternal(p.b, t.a, copy2)) {
                bindings.clear();
                bindings.putAll(copy2);
                return true;
            }

            return false;
        }

        // Case 6: Minus (Non-commutative)
        if (pattern instanceof Minus) {
            if (!(target instanceof Minus)) return false;
            Minus p = (Minus) pattern;
            Minus t = (Minus) target;
            return matchInternal(p.a, t.a, bindings) && matchInternal(p.b, t.b, bindings);
        }

        // Case 7: Divide (Non-commutative)
        if (pattern instanceof Divide) {
            if (!(target instanceof Divide)) return false;
            Divide p = (Divide) pattern;
            Divide t = (Divide) target;
            return matchInternal(p.a, t.a, bindings) && matchInternal(p.b, t.b, bindings);
        }

        // Case 8: Power (Non-commutative)
        if (pattern instanceof Power) {
            if (!(target instanceof Power)) return false;
            Power p = (Power) pattern;
            Power t = (Power) target;
            return matchInternal(p.base, t.base, bindings) && matchInternal(p.exp, t.exp, bindings);
        }

        // Case 9: UnaryMinus
        if (pattern instanceof UnaryMinus) {
            if (!(target instanceof UnaryMinus)) return false;
            UnaryMinus p = (UnaryMinus) pattern;
            UnaryMinus t = (UnaryMinus) target;
            return matchInternal(p.a, t.a, bindings);
        }

        // Case 10: FunctionCall
        if (pattern instanceof FunctionCall) {
            if (!(target instanceof FunctionCall)) return false;
            FunctionCall p = (FunctionCall) pattern;
            FunctionCall t = (FunctionCall) target;
            if (!p.name.equalsIgnoreCase(t.name) || p.args.size() != t.args.size()) {
                return false;
            }
            for (int i = 0; i < p.args.size(); i++) {
                if (!matchInternal(p.args.get(i), t.args.get(i), bindings)) {
                    return false;
                }
            }
            return true;
        }

        return false;
    }
}

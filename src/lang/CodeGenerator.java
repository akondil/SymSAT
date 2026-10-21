package lang;

import java.util.*;

/**
 * Java and C source emitter for supported SymSAT expressions.
 * Translates a symbolic AST into Java/C expressions using binary64 arithmetic.
 * The Java emitter shares structurally repeated non-leaf expressions.
 */
public class CodeGenerator {
    private static final Set<String> JAVA_RESERVED = new HashSet<>(Arrays.asList(
            ("abstract assert boolean break byte case catch char class const continue default do double else enum "
            + "extends final finally float for goto if implements import instanceof int interface long native new "
            + "package private protected public return short static strictfp super switch synchronized this throw "
            + "throws transient try void volatile while true false null record sealed permits yield var when "
            + "Math Double IllegalArgumentException").split(" ")));
    private static final Set<String> C_RESERVED = new HashSet<>(Arrays.asList(
            ("auto break case char const continue default do double else enum extern float for goto if inline int "
            + "long register restrict return short signed sizeof static struct switch typedef union unsigned void "
            + "volatile while alignas alignof bool constexpr false nullptr static_assert thread_local true typeof "
            + "typeof_unqual NAN INFINITY EDOM errno isfinite sin cos exp log pow").split(" ")));

    private static void validateName(String name, boolean c) {
        if (name == null || !name.matches("[a-zA-Z][a-zA-Z0-9_]*")
                || (c ? C_RESERVED : JAVA_RESERVED).contains(name))
            throw new IllegalArgumentException("Invalid " + (c ? "C" : "Java") + " identifier: " + name);
    }

    private static void validate(String name, Exp expression, List<String> parameters,
                                 Set<String> assumptions, boolean c) {
        validateName(name, c);
        SearchLimits.defaults().validateInput(expression);
        Set<String> names = new HashSet<>();
        for (String parameter : parameters) {
            validateName(parameter, c);
            if (!names.add(parameter)) throw new IllegalArgumentException("Duplicate parameter: " + parameter);
        }
        if (!names.containsAll(assumptions))
            throw new IllegalArgumentException("All assumed symbols must be function parameters: " + new TreeSet<>(assumptions));
        validateExpression(Objects.requireNonNull(expression), names);
    }

    private static void validateExpression(Exp e, Set<String> parameters) {
        if (e instanceof Const) return;
        if (e instanceof Symbol) {
            if (!parameters.contains(((Symbol) e).name))
                throw new IllegalArgumentException("Missing function parameter: " + ((Symbol) e).name);
            return;
        }
        Exp a, b;
        if (e instanceof Plus) { a = ((Plus) e).a; b = ((Plus) e).b; }
        else if (e instanceof Minus) { a = ((Minus) e).a; b = ((Minus) e).b; }
        else if (e instanceof Multiply) { a = ((Multiply) e).a; b = ((Multiply) e).b; }
        else if (e instanceof Divide) { a = ((Divide) e).a; b = ((Divide) e).b; }
        else if (e instanceof Power) { a = ((Power) e).base; b = ((Power) e).exp; }
        else if (e instanceof UnaryMinus) { validateExpression(((UnaryMinus) e).a, parameters); return; }
        else if (e instanceof FunctionCall) {
            FunctionCall f = (FunctionCall) e;
            if (f.args.size() != 1 || !Set.of("sin", "cos", "ln", "exp").contains(f.name.toLowerCase(Locale.ROOT)))
                throw new UnsupportedOperationException("Code generation requires sin/cos/ln/exp with one argument: " + f.name);
            validateExpression(f.args.get(0), parameters);
            return;
        } else throw new UnsupportedOperationException("Unsupported expression: " + e.getClass());
        validateExpression(a, parameters);
        validateExpression(b, parameters);
    }

    private static String contract(Set<String> assumptions) {
        if (assumptions.isEmpty()) return "";
        List<String> terms = new ArrayList<>();
        for (String symbol : new TreeSet<>(assumptions)) terms.add(symbol + " != 0");
        return "// Requires: " + String.join(", ", terms) + "\n";
    }

    private static void appendChecks(StringBuilder sb, List<String> parameters, Set<String> assumptions, boolean c) {
        for (String parameter : parameters) {
            sb.append("    if (!").append(c ? "isfinite(" : "Double.isFinite(").append(parameter).append(")");
            if (assumptions.contains(parameter)) sb.append(" || ").append(parameter).append(" == 0.0");
            if (c) sb.append(") { errno = EDOM; return NAN; }\n");
            else sb.append(") throw new IllegalArgumentException(\"Invalid input: ").append(parameter).append("\");\n");
        }
    }

    /**
     * Generates an optimized standalone Java method for evaluating the symbolic expression.
     */
    public static String toJavaMethod(String methodName, Exp expr, List<String> parameters) {
        return toJavaMethod(methodName, expr, parameters, Collections.emptySet());
    }

    public static String toJavaMethod(String methodName, Exp expr, List<String> parameters, Set<String> assumptions) {
        validate(methodName, expr, parameters, assumptions, false);
        StringBuilder sb = new StringBuilder();
        sb.append(contract(assumptions));
        sb.append("public static double ").append(methodName).append("(");
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("double ").append(parameters.get(i));
        }
        sb.append(") {\n");
        appendChecks(sb, parameters, assumptions, false);
        Map<Exp,Integer> occurrences=new HashMap<>();
        countOccurrences(expr,occurrences);
        Map<Exp,String> temporaries=new LinkedHashMap<>();
        appendTemporaries(expr,occurrences,temporaries,sb);
        sb.append("    return ").append(toJavaExpr(expr,temporaries)).append(";\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Generates C source code for the supported expression nodes.
     */
    public static String toCFunction(String functionName, Exp expr, List<String> parameters) {
        return toCFunction(functionName, expr, parameters, Collections.emptySet());
    }

    public static String toCFunction(String functionName, Exp expr, List<String> parameters, Set<String> assumptions) {
        validate(functionName, expr, parameters, assumptions, true);
        StringBuilder sb = new StringBuilder();
        sb.append(contract(assumptions));
        sb.append("#include <math.h>\n#include <errno.h>\n");
        sb.append("double ").append(functionName).append("(");
        if (parameters.isEmpty()) sb.append("void");
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("double ").append(parameters.get(i));
        }
        sb.append(") {\n");
        appendChecks(sb, parameters, assumptions, true);
        sb.append("    return ").append(toCExpr(expr)).append(";\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void countOccurrences(Exp expression, Map<Exp,Integer> counts) {
        counts.merge(expression,1,Integer::sum);
        for(Exp child:SearchLimits.children(expression))countOccurrences(child,counts);
    }

    private static void appendTemporaries(Exp expression,Map<Exp,Integer> counts,
                                          Map<Exp,String> temporaries,StringBuilder output) {
        if(temporaries.containsKey(expression))return;
        for(Exp child:SearchLimits.children(expression))appendTemporaries(child,counts,temporaries,output);
        if(counts.get(expression)>1 && !(expression instanceof Const) && !(expression instanceof Symbol)) {
            String value=toJavaExpr(expression,temporaries);
            String name="_symsat"+temporaries.size();
            temporaries.put(expression,name);
            output.append("    double ").append(name).append(" = ").append(value).append(";\n");
        }
    }

    private static String toJavaExpr(Exp e,Map<Exp,String> temporaries) {
        String shared=temporaries.get(e);
        if(shared!=null)return shared;
        if (e instanceof Const) {
            return Double.toHexString(Binary64.fromRational(((Const)e).val));
        }
        if (e instanceof Symbol) {
            return ((Symbol) e).name;
        }
        if (e instanceof Plus) {
            return "(" + toJavaExpr(((Plus) e).a, temporaries) + " + " + toJavaExpr(((Plus) e).b, temporaries) + ")";
        }
        if (e instanceof Minus) {
            return "(" + toJavaExpr(((Minus) e).a, temporaries) + " - " + toJavaExpr(((Minus) e).b, temporaries) + ")";
        }
        if (e instanceof Multiply) {
            return "(" + toJavaExpr(((Multiply) e).a, temporaries) + " * " + toJavaExpr(((Multiply) e).b, temporaries) + ")";
        }
        if (e instanceof Divide) {
            return "(" + toJavaExpr(((Divide) e).a, temporaries) + " / " + toJavaExpr(((Divide) e).b, temporaries) + ")";
        }
        if (e instanceof Power) {
            return "Math.pow(" + toJavaExpr(((Power) e).base, temporaries) + ", " + toJavaExpr(((Power) e).exp, temporaries) + ")";
        }
        if (e instanceof UnaryMinus) {
            return "(-" + toJavaExpr(((UnaryMinus) e).a, temporaries) + ")";
        }
        if (e instanceof FunctionCall) {
            FunctionCall f = (FunctionCall) e;
            String fn = f.name.toLowerCase(Locale.ROOT);
            if (f.args.size() != 1) throw new UnsupportedOperationException("Code generation requires a unary function: " + f.name);
            String argStr = toJavaExpr(f.args.get(0), temporaries);
            switch (fn) {
                case "sin": return "Math.sin(" + argStr + ")";
                case "cos": return "Math.cos(" + argStr + ")";
                case "ln": return "Math.log(" + argStr + ")";
                case "exp": return "Math.exp(" + argStr + ")";
                default: return fn + "(" + argStr + ")";
            }
        }
        return e.toString();
    }

    private static String toCExpr(Exp e) {
        if (e instanceof Const) {
            Rational r = ((Const) e).val;
            if (r.isInteger()) return r.getNum().toString() + ".0";
            return "(" + r.getNum() + ".0 / " + r.getDen() + ".0)";
        }
        if (e instanceof Symbol) {
            return ((Symbol) e).name;
        }
        if (e instanceof Plus) {
            return "(" + toCExpr(((Plus) e).a) + " + " + toCExpr(((Plus) e).b) + ")";
        }
        if (e instanceof Minus) {
            return "(" + toCExpr(((Minus) e).a) + " - " + toCExpr(((Minus) e).b) + ")";
        }
        if (e instanceof Multiply) {
            return "(" + toCExpr(((Multiply) e).a) + " * " + toCExpr(((Multiply) e).b) + ")";
        }
        if (e instanceof Divide) {
            return "(" + toCExpr(((Divide) e).a) + " / " + toCExpr(((Divide) e).b) + ")";
        }
        if (e instanceof Power) {
            return "pow(" + toCExpr(((Power) e).base) + ", " + toCExpr(((Power) e).exp) + ")";
        }
        if (e instanceof UnaryMinus) {
            return "(-" + toCExpr(((UnaryMinus) e).a) + ")";
        }
        if (e instanceof FunctionCall) {
            FunctionCall f = (FunctionCall) e;
            String fn = f.name.toLowerCase(Locale.ROOT);
            if (f.args.size() != 1) throw new UnsupportedOperationException("Code generation requires a unary function: " + f.name);
            String argStr = toCExpr(f.args.get(0));
            switch (fn) {
                case "sin": return "sin(" + argStr + ")";
                case "cos": return "cos(" + argStr + ")";
                case "ln": return "log(" + argStr + ")";
                case "exp": return "exp(" + argStr + ")";
                default: return fn + "(" + argStr + ")";
            }
        }
        return e.toString();
    }
}

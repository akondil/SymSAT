package lang;

import java.util.*;

/** Conservative real-domain facts. Unknown is never treated as false or as defined.
 * Symbols denote defined real values; 0^0 follows Rational's convention of 1.
 * Facts are valid only relative to sound active equations, like the e-graph itself.
 */
final class DomainAnalysis {
    static boolean isDefined(Exp expression) { return inspect(expression, false); }
    static boolean isNonzero(Exp expression) { return inspect(expression, true); }

    private static boolean inspect(Exp expression, boolean requireNonzero) {
        EGraph graph = new EGraph();
        int root = graph.add(expression);
        graph.saturateWithResult(Collections.emptyList(), Context.DEFAULT_MAX_ITERATIONS,
                Context.DEFAULT_NODE_LIMIT);
        DomainAnalysis facts = new DomainAnalysis(graph);
        return requireNonzero ? facts.isNonzero(root) : facts.isDefined(root);
    }

    private final EGraph graph;
    private final Set<Integer> defined = new HashSet<>();
    private final Set<Integer> nonzero = new HashSet<>();

    DomainAnalysis(EGraph graph) {
        this.graph = graph;
        boolean changed;
        do {
            changed = false;
            for (EClass ec : graph.getAllClasses()) {
                int id = graph.find(ec.id);
                for (ENode n : ec.nodes) {
                    graph.chargeWork();
                    boolean d = false, nz = false;
                    if (n.isConst()) {
                        d = true;
                        nz = !n.getConstVal().isZero();
                    } else if (n.isSymbol()) {
                        d = true;
                        nz = graph.isAssumedNonzero(n.getSymbolName());
                    } else if (n.children.size() == 2) {
                        int a = n.children.get(0), b = n.children.get(1);
                        switch (n.op) {
                            case "+": case "-": case "*":
                                d = isDefined(a) && isDefined(b);
                                nz = n.op.equals("*") && isNonzero(a) && isNonzero(b);
                                break;
                            case "/":
                                d = isDefined(a) && isNonzero(b);
                                nz = d && isNonzero(a);
                                break;
                            case "^":
                                Rational exponent = constant(b);
                                if (exponent != null && exponent.isInteger()) {
                                    d = isDefined(a) && (!exponent.isNegative() || isNonzero(a));
                                    nz = d && (exponent.isZero() || isNonzero(a));
                                }
                                break;
                            default: break;
                        }
                    } else if (n.children.size() == 1) {
                        int a = n.children.get(0);
                        switch (n.op) {
                            case "neg":
                                d = isDefined(a); nz = isNonzero(a); break;
                            case "sin": case "cos": case "exp":
                                d = isDefined(a); nz = d && n.op.equals("exp"); break;
                            case "ln":
                                Rational argument = constant(a);
                                d = argument != null && argument.compareTo(Rational.ZERO) > 0;
                                nz = d && !argument.isOne();
                                break;
                            default: break;
                        }
                    }
                    if (d) changed |= defined.add(id);
                    if (nz) changed |= nonzero.add(id);
                }
            }
        } while (changed);
    }

    boolean isDefined(int id) { return defined.contains(graph.find(id)); }
    boolean isNonzero(int id) { return nonzero.contains(graph.find(id)); }

    private Rational constant(int id) {
        for (ENode n : graph.getClass(id).nodes) if (n.isConst()) return n.getConstVal();
        return null;
    }
}

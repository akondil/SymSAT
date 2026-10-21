package lang;

import java.util.*;

/**
 * Extracts a cost-guided AST representative from an E-Graph.
 */
public class Extractor {
    private final EGraph egraph;
    private final CostFunction costFunction;
    private final Map<Integer, Double> costs = new HashMap<>();
    private final Map<Integer, ENode> bestNodes = new HashMap<>();
    private final SearchBudget extractionBudget;

    public Extractor(EGraph egraph, CostFunction costFunction) {
        this.egraph = Objects.requireNonNull(egraph);
        this.costFunction = Objects.requireNonNull(costFunction);
        this.extractionBudget = new SearchBudget(egraph.searchLimits());
        computeCosts();
    }

    private void computeCosts() {
        boolean changed = true;
        int iterations = 0;
        int maxIterations = egraph.size() * 3 + 10;

        while (changed && iterations < maxIterations) {
            changed = false;
            iterations++;

            for (EClass ec : egraph.getAllClasses()) {
                int classId = egraph.find(ec.id);
                for (ENode node : ec.nodes) {
                    extractionBudget.work();
                    boolean allChildrenKnown = true;
                    List<Double> childCosts = new ArrayList<>(node.children.size());

                    for (int childId : node.children) {
                        int canChild = egraph.find(childId);
                        Double cCost = costs.get(canChild);
                        if (cCost == null || Double.isInfinite(cCost)) {
                            allChildrenKnown = false;
                            break;
                        }
                        childCosts.add(cCost);
                    }

                    if (allChildrenKnown) {
                        double nodeCost = costFunction.cost(node, childCosts);
                        Double currentBest = costs.get(classId);

                        if (currentBest == null || nodeCost < currentBest) {
                            costs.put(classId, nodeCost);
                            bestNodes.put(classId, node);
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    public double getCost(int classId) {
        return costs.getOrDefault(egraph.find(classId), Double.POSITIVE_INFINITY);
    }

    public Exp extract(int classId) {
        return extract(classId, 1, new int[] {0});
    }

    private Exp extract(int classId, int depth, int[] nodes) {
        SearchLimits limits = egraph.searchLimits();
        if (depth > limits.maxDepth || ++nodes[0] > limits.maxInputNodes)
            throw new SearchLimitException(EGraph.StopReason.INPUT_LIMIT, "Extracted expression size or depth limit reached");
        classId = egraph.find(classId);
        ENode node = bestNodes.get(classId);
        if (node == null) {
            throw new IllegalStateException("Failed to extract expression from EClass " + classId + " (no finite cost node found)");
        }

        if (node.isConst()) {
            return new Const(node.getConstVal());
        }
        if (node.isSymbol()) {
            return new Symbol(node.getSymbolName());
        }

        switch (node.op) {
            case "+":
                return new Plus(extract(node.children.get(0), depth+1, nodes), extract(node.children.get(1), depth+1, nodes));
            case "-":
                return new Minus(extract(node.children.get(0), depth+1, nodes), extract(node.children.get(1), depth+1, nodes));
            case "*":
                return new Multiply(extract(node.children.get(0), depth+1, nodes), extract(node.children.get(1), depth+1, nodes));
            case "/":
                return new Divide(extract(node.children.get(0), depth+1, nodes), extract(node.children.get(1), depth+1, nodes));
            case "^":
                return new Power(extract(node.children.get(0), depth+1, nodes), extract(node.children.get(1), depth+1, nodes));
            case "neg":
                return new UnaryMinus(extract(node.children.get(0), depth+1, nodes));
            default:
                // Function call
                List<Exp> args = new ArrayList<>(node.children.size());
                for (int childId : node.children) {
                    args.add(extract(childId, depth+1, nodes));
                }
                return new FunctionCall(node.op, args);
        }
    }
}

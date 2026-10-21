package lang;

import java.util.List;

/**
 * Cost functions used to compare candidate representatives during extraction.
 */
public interface CostFunction {
    double cost(ENode node, List<Double> childCosts);

    /**
     * Standard AST Size cost: minimizes total number of operators and terms.
     */
    CostFunction AST_SIZE = new CostFunction() {
        @Override
        public double cost(ENode node, List<Double> childCosts) {
            double total = 1.0;
            for (Double c : childCosts) {
                total += c;
            }
            return total;
        }
    };

    /**
     * Operation cost: penalizes expensive operations (like power, division, transcendental functions)
     * over addition and subtraction to optimize for numerical computation.
     */
    CostFunction OPERATION_COST = new CostFunction() {
        @Override
        public double cost(ENode node, List<Double> childCosts) {
            double nodeCost = 1.0;
            switch (node.op) {
                case "+":
                case "-":
                case "neg":
                    nodeCost = 1.0;
                    break;
                case "*":
                    nodeCost = 3.0;
                    break;
                case "/":
                    nodeCost = 5.0;
                    break;
                case "^":
                    nodeCost = 10.0;
                    break;
                case "sin":
                case "cos":
                case "ln":
                case "exp":
                    nodeCost = 20.0;
                    break;
                default:
                    nodeCost = 0.1; // constants and symbols
                    break;
            }
            double total = nodeCost;
            for (Double c : childCosts) {
                total += c;
            }
            return total;
        }
    };
}

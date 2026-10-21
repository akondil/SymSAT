package lang;

import java.util.*;

/**
 * E-Graph for bounded equality saturation over symbolic expressions.
 * It retains discovered alternatives while explicit limits bound graph growth.
 */
public class EGraph {
    public enum StopReason {
        FIXED_POINT,
        ITERATION_LIMIT,
        NODE_LIMIT,
        COST_LOWER_BOUND,
        WORK_LIMIT,
        MATCH_LIMIT,
        INTEGER_LIMIT,
        INPUT_LIMIT,
        EQUIVALENCE_FOUND
    }

    public static final class SaturationResult {
        public final int iterations;
        public final StopReason stopReason;
        public final int eclassCount;
        public final int enodeCount;
        public final long workUnits, bindingRecords;

        private SaturationResult(int iterations, StopReason stopReason, int eclassCount, int enodeCount) {
            this(iterations, stopReason, eclassCount, enodeCount, 0, 0);
        }

        private SaturationResult(int iterations, StopReason stopReason, int eclassCount, int enodeCount, long work, long bindings) {
            this.workUnits=work; this.bindingRecords=bindings;
            this.iterations = iterations;
            this.stopReason = stopReason;
            this.eclassCount = eclassCount;
            this.enodeCount = enodeCount;
        }
    }

    private static final class NodeLimitReached extends SearchLimitException {
        private static final long serialVersionUID = 1L;
        NodeLimitReached() { super(StopReason.NODE_LIMIT, "Graph node limit reached"); }
    }

    private final Map<Integer, Integer> unionFind = new HashMap<>();
    private final Map<Integer, EClass> classes = new HashMap<>();
    private final Map<ENode, Integer> memo = new HashMap<>();
    private int nextId = 0;
    private boolean clean = true;
    private long mutationCount = 0;
    private int activeNodeLimit = Integer.MAX_VALUE;
    private final boolean commutativeOperatorsEnabled;
    private final boolean constantFoldingEnabled;
    private final Set<String> nonzeroSymbols;
    private final SearchLimits limits;
    private SearchBudget budget;
    void chargeWork() { if (budget != null) budget.work(); }
    private void chargeBinding() { if (budget != null) budget.binding(); }
    SearchLimits searchLimits() { return limits; }
    SaturationResult inputStopped(StopReason reason) { return new SaturationResult(0, reason, size(), totalNodeCount()); }

    public EGraph() {
        this(true, true);
    }

    /**
     * Creates an e-graph with optional built-in commutative handling and constant folding.
     * These switches support controlled ablations; both features are enabled by default.
     */
    public EGraph(boolean commutativeOperatorsEnabled, boolean constantFoldingEnabled) {
        this(commutativeOperatorsEnabled, constantFoldingEnabled, Collections.emptySet(), SearchLimits.defaults());
    }

    public EGraph(Set<String> nonzeroSymbols) {
        this(true, true, nonzeroSymbols, SearchLimits.defaults());
    }

    public EGraph(Set<String> nonzeroSymbols, SearchLimits limits) {
        this(true, true, nonzeroSymbols, limits);
    }

    private EGraph(boolean commutativeOperatorsEnabled, boolean constantFoldingEnabled,
                   Set<String> nonzeroSymbols, SearchLimits limits) {
        this.limits=Objects.requireNonNull(limits);
        this.activeNodeLimit=limits.nodeLimit;
        this.commutativeOperatorsEnabled = commutativeOperatorsEnabled;
        this.constantFoldingEnabled = constantFoldingEnabled;
        TreeSet<String> snapshot = new TreeSet<>(Objects.requireNonNull(nonzeroSymbols));
        for (String symbol : snapshot) {
            if (!symbol.matches("[a-zA-Z][a-zA-Z0-9_]*"))
                throw new IllegalArgumentException("Assumptions require ordinary symbol names: " + symbol);
        }
        this.nonzeroSymbols = Collections.unmodifiableSet(snapshot);
    }

    boolean isAssumedNonzero(String symbol) { return nonzeroSymbols.contains(symbol); }

    public int find(int id) {
        int root = id;
        while (unionFind.containsKey(root) && unionFind.get(root) != root) {
            root = unionFind.get(root);
        }
        int curr = id;
        while (unionFind.containsKey(curr) && unionFind.get(curr) != curr) {
            int next = unionFind.get(curr);
            unionFind.put(curr, root);
            curr = next;
        }
        return root;
    }

    public ENode canonicalize(ENode node) {
        List<Integer> canChildren = new ArrayList<>(node.children.size());
        for (int c : node.children) {
            canChildren.add(find(c));
        }
        if (commutativeOperatorsEnabled
                && (node.op.equals("+") || node.op.equals("*"))
                && canChildren.size() == 2
                && canChildren.get(0) > canChildren.get(1)) {
            Collections.swap(canChildren, 0, 1);
        }
        return new ENode(node.op, canChildren);
    }

    public EClass getClass(int id) {
        return classes.get(find(id));
    }

    public Collection<EClass> getAllClasses() {
        return classes.values();
    }

    public int size() {
        return classes.size();
    }

    public int totalNodeCount() {
        int count = 0;
        for (EClass ec : classes.values()) {
            count += ec.nodes.size();
        }
        return count;
    }

    public int add(Exp expr) {
        limits.validateInput(expr);
        return addUnchecked(expr);
    }

    private int addUnchecked(Exp expr) {
        if (expr instanceof Const) {
            return addENode(ENode.ofConst(((Const) expr).val));
        } else if (expr instanceof Symbol) {
            return addENode(ENode.ofSymbol(((Symbol) expr).name));
        } else if (expr instanceof Plus) {
            Plus p = (Plus) expr;
            return addENode(new ENode("+", Arrays.asList(addUnchecked(p.a), addUnchecked(p.b))));
        } else if (expr instanceof Minus) {
            Minus m = (Minus) expr;
            return addENode(new ENode("-", Arrays.asList(addUnchecked(m.a), addUnchecked(m.b))));
        } else if (expr instanceof Multiply) {
            Multiply m = (Multiply) expr;
            return addENode(new ENode("*", Arrays.asList(addUnchecked(m.a), addUnchecked(m.b))));
        } else if (expr instanceof Divide) {
            Divide d = (Divide) expr;
            return addENode(new ENode("/", Arrays.asList(addUnchecked(d.a), addUnchecked(d.b))));
        } else if (expr instanceof Power) {
            Power p = (Power) expr;
            return addENode(new ENode("^", Arrays.asList(addUnchecked(p.base), addUnchecked(p.exp))));
        } else if (expr instanceof UnaryMinus) {
            UnaryMinus u = (UnaryMinus) expr;
            return addENode(new ENode("neg", Collections.singletonList(addUnchecked(u.a))));
        } else if (expr instanceof FunctionCall) {
            FunctionCall f = (FunctionCall) expr;
            List<Integer> childIds = new ArrayList<>();
            for (Exp arg : f.args) {
                childIds.add(addUnchecked(arg));
            }
            return addENode(new ENode(f.name.toLowerCase(), childIds));
        }
        throw new UnsupportedOperationException("Unknown Exp type for EGraph: " + expr.getClass());
    }

    public int addENode(ENode node) {
        chargeWork();
        if (node.isConst()) limits.checkRational(node.getConstVal());
        ENode can = canonicalize(node);
        if (memo.containsKey(can)) {
            return find(memo.get(can));
        }
        if (totalNodeCount() >= activeNodeLimit) {
            throw new NodeLimitReached();
        }

        int id = nextId++;
        unionFind.put(id, id);
        EClass eClass = new EClass(id);
        eClass.nodes.add(can);
        classes.put(id, eClass);
        memo.put(can, id);
        mutationCount++;

        for (int childId : can.children) {
            EClass childClass = classes.get(find(childId));
            if (childClass != null) {
                childClass.parents.add(new EClass.ParentRef(can, id));
            }
        }
        return id;
    }

    public int union(int id1, int id2) {
        chargeWork();
        id1 = find(id1);
        id2 = find(id2);
        if (id1 == id2) return id1;

        // Make id1 the root
        unionFind.put(id2, id1);
        mutationCount++;
        EClass c1 = classes.get(id1);
        EClass c2 = classes.remove(id2);

        if (c2 != null) {
            c1.nodes.addAll(c2.nodes);
            c1.parents.addAll(c2.parents);
        }
        clean = false;
        return id1;
    }

    public void rebuild() {
        while (!clean) {
            clean = true;
            Map<ENode, Integer> newMemo = new HashMap<>();

            for (Map.Entry<ENode, Integer> entry : memo.entrySet()) {
                chargeWork();
                ENode can = canonicalize(entry.getKey());
                int classId = find(entry.getValue());

                if (newMemo.containsKey(can)) {
                    int existingClass = newMemo.get(can);
                    if (find(existingClass) != find(classId)) {
                        union(existingClass, classId);
                        clean = false;
                    }
                } else {
                    newMemo.put(can, classId);
                }
            }

            memo.clear();
            memo.putAll(newMemo);

            // Update nodes in all classes
            for (EClass ec : classes.values()) {
                Set<ENode> updatedNodes = new HashSet<>();
                for (ENode n : ec.nodes) {
                    updatedNodes.add(canonicalize(n));
                }
                ec.nodes.clear();
                ec.nodes.addAll(updatedNodes);
            }
        }
    }

    /**
     * E-Matching: Finds all instances of a pattern in the E-Graph.
     * Returns a list of Match instances (EClass ID + Variable Bindings).
     */
    public static class Match {
        public final int eclassId;
        public final Map<String, Integer> bindings;

        public Match(int eclassId, Map<String, Integer> bindings) {
            this.eclassId = eclassId;
            this.bindings = Collections.unmodifiableMap(new HashMap<>(bindings));
        }
    }

    public List<Match> ematch(Exp pattern) {
        List<Match> results = new ArrayList<>();
        List<Integer> classIds = new ArrayList<>(classes.keySet());
        for (int cid : classIds) {
            chargeWork();
            List<Map<String, Integer>> subMatches = ematchInClass(pattern, cid);
            for (Map<String, Integer> subst : subMatches) {
                chargeBinding();
                results.add(new Match(cid, subst));
            }
        }
        return results;
    }

    private List<Map<String, Integer>> ematchInClass(Exp pattern, int classId) {
        chargeWork();
        classId = find(classId);

        // Pattern variable: ?x, ?y
        if (pattern instanceof Symbol && ((Symbol) pattern).isPatternVariable()) {
            chargeBinding();
            Map<String, Integer> binding = new HashMap<>();
            binding.put(((Symbol) pattern).name, classId);
            return Collections.singletonList(binding);
        }

        EClass eClass = classes.get(classId);
        if (eClass == null) return Collections.emptyList();

        List<Map<String, Integer>> allMatches = new ArrayList<>();

        for (ENode node : eClass.nodes) {
            List<Map<String, Integer>> nodeMatches = matchENode(pattern, node);
            allMatches.addAll(nodeMatches);
        }

        return allMatches;
    }

    private List<Map<String, Integer>> matchENode(Exp pattern, ENode node) {
        chargeWork();
        if (pattern instanceof Const) {
            Const c = (Const) pattern;
            if (node.isConst() && node.getConstVal().equals(c.val)) {
                return Collections.singletonList(Collections.emptyMap());
            }
            return Collections.emptyList();
        }

        if (pattern instanceof Symbol) {
            Symbol s = (Symbol) pattern;
            if (node.isSymbol() && node.getSymbolName().equals(s.name)) {
                return Collections.singletonList(Collections.emptyMap());
            }
            return Collections.emptyList();
        }

        // Composite operators
        String op = null;
        List<Exp> children = new ArrayList<>();

        if (pattern instanceof Plus) {
            op = "+";
            children.add(((Plus) pattern).a);
            children.add(((Plus) pattern).b);
        } else if (pattern instanceof Minus) {
            op = "-";
            children.add(((Minus) pattern).a);
            children.add(((Minus) pattern).b);
        } else if (pattern instanceof Multiply) {
            op = "*";
            children.add(((Multiply) pattern).a);
            children.add(((Multiply) pattern).b);
        } else if (pattern instanceof Divide) {
            op = "/";
            children.add(((Divide) pattern).a);
            children.add(((Divide) pattern).b);
        } else if (pattern instanceof Power) {
            op = "^";
            children.add(((Power) pattern).base);
            children.add(((Power) pattern).exp);
        } else if (pattern instanceof UnaryMinus) {
            op = "neg";
            children.add(((UnaryMinus) pattern).a);
        } else if (pattern instanceof FunctionCall) {
            FunctionCall f = (FunctionCall) pattern;
            op = f.name.toLowerCase();
            children.addAll(f.args);
        }

        if (op == null || !op.equalsIgnoreCase(node.op) || children.size() != node.children.size()) {
            return Collections.emptyList();
        }

        boolean commutative = commutativeOperatorsEnabled
                && (op.equals("+") || op.equals("*")) && children.size() == 2;
        List<Map<String, Integer>> matches = matchChildren(children, node, false);
        if (commutative) {
            matches.addAll(matchChildren(children, node, true));
            return new ArrayList<>(new LinkedHashSet<>(matches));
        }
        return matches;
    }

    private List<Map<String, Integer>> matchChildren(List<Exp> patternChildren, ENode node, boolean swapped) {
        List<Map<String, Integer>> currentBindings = new ArrayList<>();
        currentBindings.add(new HashMap<>());

        for (int i = 0; i < patternChildren.size(); i++) {
            int childIndex = swapped && i < 2 ? 1 - i : i;
            Exp patChild = patternChildren.get(i);
            int eclassChild = node.children.get(childIndex);
            List<Map<String, Integer>> childMatches = ematchInClass(patChild, eclassChild);

            List<Map<String, Integer>> nextBindings = new ArrayList<>();
            for (Map<String, Integer> parentB : currentBindings) {
                for (Map<String, Integer> childB : childMatches) {
                    chargeWork();
                    chargeBinding();
                    Map<String, Integer> merged = mergeBindings(parentB, childB);
                    if (merged != null) {
                        nextBindings.add(merged);
                    }
                }
            }
            currentBindings = nextBindings;
            if (currentBindings.isEmpty()) break;
        }
        return currentBindings;
    }

    private Map<String, Integer> mergeBindings(Map<String, Integer> b1, Map<String, Integer> b2) {
        Map<String, Integer> merged = new HashMap<>(b1);
        for (Map.Entry<String, Integer> entry : b2.entrySet()) {
            if (merged.containsKey(entry.getKey())) {
                if (find(merged.get(entry.getKey())) != find(entry.getValue())) {
                    return null; // Conflict
                }
            } else {
                merged.put(entry.getKey(), find(entry.getValue()));
            }
        }
        return merged;
    }

    /**
     * Adds an instantiated template pattern into the EGraph given variable bindings.
     */
    public int addInstantiated(Exp template, Map<String, Integer> bindings) {
        chargeWork();
        if (template instanceof Symbol && ((Symbol) template).isPatternVariable()) {
            String pName = ((Symbol) template).name;
            if (bindings.containsKey(pName)) {
                return find(bindings.get(pName));
            }
            throw new IllegalStateException("Unbound pattern variable: " + pName);
        }

        if (template instanceof Const) {
            return addENode(ENode.ofConst(((Const) template).val));
        } else if (template instanceof Symbol) {
            return addENode(ENode.ofSymbol(((Symbol) template).name));
        } else if (template instanceof Plus) {
            Plus p = (Plus) template;
            return addENode(new ENode("+", Arrays.asList(addInstantiated(p.a, bindings), addInstantiated(p.b, bindings))));
        } else if (template instanceof Minus) {
            Minus m = (Minus) template;
            return addENode(new ENode("-", Arrays.asList(addInstantiated(m.a, bindings), addInstantiated(m.b, bindings))));
        } else if (template instanceof Multiply) {
            Multiply m = (Multiply) template;
            return addENode(new ENode("*", Arrays.asList(addInstantiated(m.a, bindings), addInstantiated(m.b, bindings))));
        } else if (template instanceof Divide) {
            Divide d = (Divide) template;
            return addENode(new ENode("/", Arrays.asList(addInstantiated(d.a, bindings), addInstantiated(d.b, bindings))));
        } else if (template instanceof Power) {
            Power p = (Power) template;
            return addENode(new ENode("^", Arrays.asList(addInstantiated(p.base, bindings), addInstantiated(p.exp, bindings))));
        } else if (template instanceof UnaryMinus) {
            UnaryMinus u = (UnaryMinus) template;
            return addENode(new ENode("neg", Collections.singletonList(addInstantiated(u.a, bindings))));
        } else if (template instanceof FunctionCall) {
            FunctionCall f = (FunctionCall) template;
            List<Integer> childIds = new ArrayList<>();
            for (Exp arg : f.args) {
                childIds.add(addInstantiated(arg, bindings));
            }
            return addENode(new ENode(f.name.toLowerCase(), childIds));
        }
        throw new UnsupportedOperationException("Unknown template type: " + template.getClass());
    }

    /**
     * Executes Equality Saturation by repeatedly applying rules until no new equivalences
     * are discovered or iteration limit is reached.
     */
    public int saturate(List<Rule> rules, int maxIterations) {
        return saturate(rules, maxIterations, 5000);
    }

    public int saturate(List<Rule> rules, int maxIterations, int nodeLimit) {
        return saturateWithResult(rules, maxIterations, nodeLimit).iterations;
    }

    public SaturationResult saturateWithResult(List<Rule> rules, int maxIterations, int nodeLimit) {
        return saturate(rules, maxIterations, nodeLimit, -1, -1);
    }

    /** A leaf attains the universal lower bound of either built-in additive metric.
     * Custom costs deliberately disable this shortcut; no bound is assumed for them.
     */
    public SaturationResult saturateForExtraction(List<Rule> rules, int maxIterations, int nodeLimit,
                                                 int rootId, CostFunction costFunction) {
        Objects.requireNonNull(costFunction, "costFunction");
        if (!unionFind.containsKey(rootId))
            throw new IllegalArgumentException("Extraction root must belong to this E-Graph");
        boolean builtIn = costFunction == CostFunction.AST_SIZE || costFunction == CostFunction.OPERATION_COST;
        return saturate(rules, maxIterations, nodeLimit, -1, -1, builtIn ? rootId : -1);
    }

    private boolean hasLeaf(int rootId) {
        if (rootId < 0) return false;
        for (ENode node : getClass(rootId).nodes)
            if (node.isConst() || node.isSymbol()) return true;
        return false;
    }

    /**
     * Saturates until the requested roots merge, a fixed point is reached, or a
     * resource limit stops the search. A merged result may stop before saturation
     * reaches a fixed point; the returned stop reason distinguishes that case.
     */
    public SaturationResult saturateUntilEquivalent(List<Rule> rules, int maxIterations,
                                                     int nodeLimit, int leftId, int rightId) {
        if (!unionFind.containsKey(leftId) || !unionFind.containsKey(rightId)) {
            throw new IllegalArgumentException("Both equivalence-query roots must belong to this E-Graph");
        }
        return saturate(rules, maxIterations, nodeLimit, leftId, rightId);
    }

    private SaturationResult saturate(List<Rule> rules, int maxIterations, int nodeLimit,
                                      int queryLeftId, int queryRightId) {
        return saturate(rules, maxIterations, nodeLimit, queryLeftId, queryRightId, -1);
    }

    private SaturationResult saturate(List<Rule> rules, int maxIterations, int nodeLimit,
                                      int queryLeftId, int queryRightId, int extractionRootId) {
        for (Rule rule : rules) { limits.validateInput(rule.left); limits.validateInput(rule.right); }
        int iterations = 0;
        boolean reachedFixedPoint = false;
        boolean reachedNodeLimit = false;
        boolean foundEquivalence = queryLeftId >= 0
            && find(queryLeftId) == find(queryRightId);
        if (foundEquivalence) {
            return new SaturationResult(0, StopReason.EQUIVALENCE_FOUND, size(), totalNodeCount());
        }
        if (hasLeaf(extractionRootId))
            return new SaturationResult(0, StopReason.COST_LOWER_BOUND, size(), totalNodeCount());
        boolean reachedCostBound = false;
        int previousNodeLimit = activeNodeLimit;
        activeNodeLimit = Math.min(previousNodeLimit, Math.max(0, nodeLimit));

        SearchBudget currentBudget = new SearchBudget(limits);
        SearchBudget previousBudget = budget;
        budget = currentBudget;
        StopReason resourceStop = null;
        try {
            while (iterations < maxIterations && totalNodeCount() < activeNodeLimit) {
                iterations++;
                long previousMutationCount = mutationCount;

                // Collect all applications first to avoid modifying graph during matching.
                List<Runnable> pendingUnions = new ArrayList<>();
                DomainAnalysis domainFacts = new DomainAnalysis(this);

                for (Rule rule : rules) {
                    List<Match> fwdMatches = ematch(rule.left);
                    for (Match m : fwdMatches) {
                        if (!rule.permits(m.bindings, domainFacts)) continue;
                        pendingUnions.add(() -> {
                            int rhsId = addInstantiated(rule.right, m.bindings);
                            union(m.eclassId, rhsId);
                        });
                    }

                    if (rule.isBidirectional) {
                        List<Match> bwdMatches = ematch(rule.right);
                        for (Match m : bwdMatches) {
                            if (!rule.permits(m.bindings, domainFacts)) continue;
                            pendingUnions.add(() -> {
                                int lhsId = addInstantiated(rule.left, m.bindings);
                                union(m.eclassId, lhsId);
                            });
                        }
                    }
                }

                try {
                    for (Runnable application : pendingUnions) {
                        application.run();
                    }
                    if (constantFoldingEnabled) {
                        constantFold();
                    }
                } catch (NodeLimitReached limitReached) {
                    reachedNodeLimit = true;
                }

                rebuild();

                if (hasLeaf(extractionRootId)) {
                    reachedCostBound = true;
                    break;
                }

                if (queryLeftId >= 0 && find(queryLeftId) == find(queryRightId)) {
                    foundEquivalence = true;
                    break;
                }

                if (reachedNodeLimit || totalNodeCount() >= activeNodeLimit) {
                    reachedNodeLimit = true;
                    break;
                }
                if (mutationCount == previousMutationCount) {
                    reachedFixedPoint = true;
                    break;
                }
            }
        } catch (SearchLimitException exceeded) {
            resourceStop = exceeded.reason;
        } finally {
            budget = null;
            rebuild(); // Restore invariants even when a budget stopped a partially applied batch.
            budget = previousBudget;
            activeNodeLimit = previousNodeLimit;
        }

        StopReason stopReason;
        if (resourceStop != null) {
            stopReason = resourceStop;
        } else if (reachedCostBound) {
            stopReason = StopReason.COST_LOWER_BOUND;
        } else if (foundEquivalence) {
            stopReason = StopReason.EQUIVALENCE_FOUND;
        } else if (reachedFixedPoint) {
            stopReason = StopReason.FIXED_POINT;
        } else if (reachedNodeLimit || totalNodeCount() >= Math.max(0, nodeLimit)) {
            stopReason = StopReason.NODE_LIMIT;
        } else {
            stopReason = StopReason.ITERATION_LIMIT;
        }
        return new SaturationResult(iterations, stopReason, size(), totalNodeCount(), currentBudget.work, currentBudget.bindings);
    }

    private void constantFold() {
        List<Runnable> folds = new ArrayList<>();
        for (EClass ec : classes.values()) {
            for (ENode node : ec.nodes) {
                chargeWork();
                if (node.isConst() || node.isSymbol()) continue;

                // Check if all children have a constant node in their e-class
                List<Rational> constArgs = new ArrayList<>();
                boolean allConst = true;
                for (int cId : node.children) {
                    EClass cClass = classes.get(find(cId));
                    Rational cVal = null;
                    if (cClass != null) {
                        for (ENode cn : cClass.nodes) {
                            if (cn.isConst()) {
                                cVal = cn.getConstVal();
                                break;
                            }
                        }
                    }
                    if (cVal == null) {
                        allConst = false;
                        break;
                    }
                    constArgs.add(cVal);
                }

                if (allConst && !constArgs.isEmpty()) {
                    try {
                        limits.checkFold(node.op, constArgs);
                        Rational folded = null;
                        if (node.op.equals("+") && constArgs.size() == 2) {
                            folded = constArgs.get(0).add(constArgs.get(1));
                        } else if (node.op.equals("-") && constArgs.size() == 2) {
                            folded = constArgs.get(0).subtract(constArgs.get(1));
                        } else if (node.op.equals("*") && constArgs.size() == 2) {
                            folded = constArgs.get(0).multiply(constArgs.get(1));
                        } else if (node.op.equals("/") && constArgs.size() == 2 && !constArgs.get(1).isZero()) {
                            folded = constArgs.get(0).divide(constArgs.get(1));
                        } else if (node.op.equals("^") && constArgs.size() == 2 && constArgs.get(1).isInteger()) {
                            folded = constArgs.get(0).pow(constArgs.get(1).getNum());
                        } else if (node.op.equals("neg") && constArgs.size() == 1) {
                            folded = constArgs.get(0).negate();
                        }

                        if (folded != null) {
                            final Rational fVal = folded;
                            final int targetClass = ec.id;
                            folds.add(() -> {
                                int constClass = addENode(ENode.ofConst(fVal));
                                union(targetClass, constClass);
                            });
                        }
                    } catch (SearchLimitException exceeded) {
                        throw exceeded;
                    } catch (ArithmeticException ignored) {
                    }
                }
            }
        }
        for (Runnable f : folds) {
            f.run();
        }
    }
}

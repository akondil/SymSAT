package lang;

import java.util.*;

/**
 * Base class for all Top-Level DSL Statements in SymSAT.
 */
public abstract class Stmt {
    public abstract Object execute(Context ctx);

    protected static void reportAssumptions(Context ctx) {
        if (!ctx.getNonzeroAssumptions().isEmpty())
            System.out.println("[Assumptions] " + ctx.assumptionSummary());
    }
}

/** assume x != 0; restricts an unbound real symbol in this context. */
class AssumeStmt extends Stmt {
    public final String symbol;
    public AssumeStmt(String symbol) { this.symbol = symbol; }
    @Override public Object execute(Context ctx) {
        ctx.assumeNonzero(symbol);
        reportAssumptions(ctx);
        return ctx.getNonzeroAssumptions();
    }
}

/**
 * rule name : pattern => template ;
 * rule name : pattern <=> template ;
 */
class RuleStmt extends Stmt {
    public final String name;
    public final Exp left;
    public final Exp right;
    public final boolean isBidirectional;

    public RuleStmt(String name, Exp left, Exp right, boolean isBidirectional) {
        this.name = name;
        this.left = left;
        this.right = right;
        this.isBidirectional = isBidirectional;
    }

    @Override
    public Object execute(Context ctx) {
        Rule rule = new Rule(name, left, right, isBidirectional);
        ctx.addRule(rule);
        System.out.println("[Rule Defined] " + rule);
        return rule;
    }
}

/**
 * let x = expr ;
 */
class LetStmt extends Stmt {
    public final String varName;
    public final Exp expr;

    public LetStmt(String varName, Exp expr) {
        this.varName = varName;
        this.expr = expr;
    }

    @Override
    public Object execute(Context ctx) {
        Exp simplified = ctx.simplify(expr, null);
        ctx.defineVar(varName, simplified);
        reportAssumptions(ctx);
        System.out.println("[Let] " + varName + " = " + simplified);
        return simplified;
    }
}

/**
 * simplify expr [ cost <metric> ] ;
 */
class SimplifyStmt extends Stmt {
    public final Exp expr;
    public final String costMetric;

    public SimplifyStmt(Exp expr, String costMetric) {
        this.expr = expr;
        this.costMetric = costMetric;
    }

    @Override
    public Object execute(Context ctx) {
        CostFunction cf = null;
        if (costMetric != null) {
            if (costMetric.equalsIgnoreCase("operation_cost")) cf = CostFunction.OPERATION_COST;
            else if (costMetric.equalsIgnoreCase("ast_size")) cf = CostFunction.AST_SIZE;
            else throw new IllegalArgumentException("Unknown cost metric: " + costMetric);
        }
        SimplificationResult result = ctx.simplifyDetailed(expr, cf);
        reportAssumptions(ctx);
        System.out.println("[Simplify] " + expr + "\n       ==> " + result.expression);
        System.out.println("[Search] " + result.summary());
        return result.expression;
    }
}

/**
 * diff expr wrt var ;
 */
class DiffStmt extends Stmt {
    public final Exp expr;
    public final String targetVar;

    public DiffStmt(Exp expr, String targetVar) {
        this.expr = expr;
        this.targetVar = targetVar;
    }

    @Override
    public Object execute(Context ctx) {
        Exp result = ctx.differentiate(expr, targetVar);
        reportAssumptions(ctx);
        System.out.println("[Diff d/d" + targetVar + "] " + expr + "\n            ==> " + result);
        return result;
    }
}

/**
 * eval expr [ where x = 3, y = 5 ] ;
 */
class EvalStmt extends Stmt {
    public final Exp expr;
    public final Map<String, Rational> bindings;

    public EvalStmt(Exp expr, Map<String, Rational> bindings) {
        this.expr = expr;
        this.bindings = bindings;
    }

    @Override
    public Object execute(Context ctx) {
        reportAssumptions(ctx);
        Rational val = ctx.evaluate(expr, bindings);
        System.out.println("[Eval] " + expr + " at " + bindings + " ==> " + val);
        return val;
    }
}

/**
 * print expr ;
 */
class PrintStmt extends Stmt {
    public final Exp expr;

    public PrintStmt(Exp expr) {
        this.expr = expr;
    }

    @Override
    public Object execute(Context ctx) {
        Exp resolved = ctx.resolve(expr);
        reportAssumptions(ctx);
        System.out.println("[Print] " + resolved);
        return resolved;
    }
}

/**
 * prove expr1 == expr2 ;
 */
class ProveStmt extends Stmt {
    public final Exp left;
    public final Exp right;

    public ProveStmt(Exp left, Exp right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object execute(Context ctx) {
        Context.EquivalenceResult result = ctx.checkEquivalence(left, right);
        reportAssumptions(ctx);
        switch (result.status) {
            case PROVED:
                System.out.println("[Prove: DERIVED under active rewrite rules]");
                break;
            case NOT_DERIVED:
                System.out.println("[Prove: NOT DERIVED at fixed point; inequivalence is not established]");
                break;
            case INCOMPLETE:
                System.out.println("[Prove: INCOMPLETE; search stopped at " + result.saturation.stopReason + "]");
                break;
        }
        System.out.println("       " + left + "\n   ==  " + right);
        System.out.println("       iterations=" + result.saturation.iterations
                + ", e-classes=" + result.saturation.eclassCount
                + ", e-nodes=" + result.saturation.enodeCount
                + ", stop=" + result.saturation.stopReason);
        return result;
    }
}

/**
 * codegen expr as <java|c> [ func_name ( arg1, arg2 ) ] ;
 */
class CodegenStmt extends Stmt {
    public final Exp expr;
    public final String targetLang;
    public final String funcName;
    public final List<String> params;

    public CodegenStmt(Exp expr, String targetLang, String funcName, List<String> params) {
        this.expr = expr;
        this.targetLang = targetLang != null ? targetLang.toLowerCase() : "java";
        this.funcName = funcName != null ? funcName : "compute";
        this.params = params != null ? params : Collections.emptyList();
    }

    @Override
    public Object execute(Context ctx) {
        if (!"c".equals(targetLang) && !"java".equals(targetLang))
            throw new IllegalArgumentException("Unsupported codegen target: " + targetLang);
        Exp simplified = ctx.simplify(expr, CostFunction.OPERATION_COST);
        String code;
        if ("c".equals(targetLang)) {
            code = CodeGenerator.toCFunction(funcName, simplified, params, ctx.getNonzeroAssumptions());
        } else {
            code = CodeGenerator.toJavaMethod(funcName, simplified, params, ctx.getNonzeroAssumptions());
        }
        System.out.println("[Generated " + targetLang.toUpperCase() + " Code]:");
        System.out.print(code);
        return code;
    }
}

/**
 * Complete SymSAT Script / Program.
 */
class Program {
    public final List<Stmt> statements;

    public Program(List<Stmt> statements) {
        this.statements = Collections.unmodifiableList(new ArrayList<>(statements));
    }

    public void execute(Context ctx) {
        for (Stmt stmt : statements) {
            stmt.execute(ctx);
        }
    }
}

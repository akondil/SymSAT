package lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Substitution Visitor: replaces symbols with target expressions [x -> expr].
 */
public class SubstVisitor implements Visitor<Exp> {
    private final Map<String, Exp> substMap;

    public SubstVisitor(String var, Exp replacement) {
        this.substMap = Collections.singletonMap(Objects.requireNonNull(var), Objects.requireNonNull(replacement));
    }

    public SubstVisitor(Map<String, Exp> substMap) {
        this.substMap = new HashMap<>(Objects.requireNonNull(substMap));
    }

    @Override
    public Exp visit(Const e) {
        return e;
    }

    @Override
    public Exp visit(Symbol e) {
        if (substMap.containsKey(e.name)) {
            return substMap.get(e.name);
        }
        return e;
    }

    @Override
    public Exp visit(Plus e) {
        return new Plus(e.a.accept(this), e.b.accept(this));
    }

    @Override
    public Exp visit(Minus e) {
        return new Minus(e.a.accept(this), e.b.accept(this));
    }

    @Override
    public Exp visit(Multiply e) {
        return new Multiply(e.a.accept(this), e.b.accept(this));
    }

    @Override
    public Exp visit(Divide e) {
        return new Divide(e.a.accept(this), e.b.accept(this));
    }

    @Override
    public Exp visit(Power e) {
        return new Power(e.base.accept(this), e.exp.accept(this));
    }

    @Override
    public Exp visit(UnaryMinus e) {
        return new UnaryMinus(e.a.accept(this));
    }

    @Override
    public Exp visit(FunctionCall e) {
        List<Exp> newArgs = new ArrayList<>();
        for (Exp arg : e.args) {
            newArgs.add(arg.accept(this));
        }
        return new FunctionCall(e.name, newArgs);
    }
}

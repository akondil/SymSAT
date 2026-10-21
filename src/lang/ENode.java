package lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An E-Node represents an operator whose children are E-Class IDs (not AST nodes).
 * This enables compact representation of exponentially many equivalent expressions.
 */
public class ENode {
    public final String op;
    public final List<Integer> children;

    public ENode(String op, List<Integer> children) {
        this.op = Objects.requireNonNull(op);
        List<Integer> list = new ArrayList<>(children);
        this.children = Collections.unmodifiableList(list);
    }

    public static ENode ofConst(Rational r) {
        return new ENode("const:" + r.toString(), Collections.emptyList());
    }

    public static ENode ofSymbol(String name) {
        return new ENode("sym:" + name, Collections.emptyList());
    }

    public boolean isConst() {
        return op.startsWith("const:");
    }

    public Rational getConstVal() {
        if (!isConst()) return null;
        return Rational.of(op.substring("const:".length()));
    }

    public boolean isSymbol() {
        return op.startsWith("sym:");
    }

    public String getSymbolName() {
        if (!isSymbol()) return null;
        return op.substring("sym:".length());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ENode eNode = (ENode) o;
        return op.equals(eNode.op) && children.equals(eNode.children);
    }

    @Override
    public int hashCode() {
        return Objects.hash(op, children);
    }

    @Override
    public String toString() {
        if (children.isEmpty()) return op;
        return op + children;
    }
}

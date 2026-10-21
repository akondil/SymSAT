package lang;

/**
 * Generic Visitor interface for AST nodes.
 * @param <T> Return type of the visitor operations.
 */
public interface Visitor<T> {
    T visit(Const e);
    T visit(Symbol e);
    T visit(Plus e);
    T visit(Minus e);
    T visit(Multiply e);
    T visit(Divide e);
    T visit(Power e);
    T visit(UnaryMinus e);
    T visit(FunctionCall e);
}

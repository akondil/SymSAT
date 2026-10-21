package lang;

/** Typed resource exhaustion. Saturation loops convert it into a structured stop result. */
public class SearchLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final EGraph.StopReason reason;
    public SearchLimitException(EGraph.StopReason reason, String message) {
        super(message); this.reason=reason;
    }
}

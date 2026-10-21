package lang;

/** Counts search work units and produced substitution records before allocation. */
final class SearchBudget {
    final SearchLimits limits;
    long work, bindings;
    SearchBudget(SearchLimits limits) { this.limits=limits; }
    void work() {
        if(work>=limits.maxWork)throw new SearchLimitException(EGraph.StopReason.WORK_LIMIT,"Search work limit reached");
        work++;
    }
    void binding() {
        if(bindings>=limits.maxBindings)throw new SearchLimitException(EGraph.StopReason.MATCH_LIMIT,"Binding record limit reached");
        bindings++;
    }
}

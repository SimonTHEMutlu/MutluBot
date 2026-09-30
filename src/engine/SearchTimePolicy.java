package engine;

/** Pure completed-iteration and hard-budget decisions for iterative search. */
final class SearchTimePolicy {
    private SearchTimePolicy() { }

    static boolean shouldStopAtSoftAfterCompletedIteration(long elapsedNanos,
                                                           long softBudgetNanos,
                                                           long hardBudgetNanos,
                                                           boolean stable) {
        return stable && softBudgetNanos >= 0 && softBudgetNanos < hardBudgetNanos
                && elapsedNanos >= softBudgetNanos;
    }

    static boolean shouldStartNextIteration(long elapsedNanos,
                                            long predictedIterationNanos,
                                            long hardBudgetNanos) {
        if (hardBudgetNanos < 0) return true;
        if (elapsedNanos >= hardBudgetNanos) return false;
        return predictedIterationNanos < hardBudgetNanos - elapsedNanos;
    }
}

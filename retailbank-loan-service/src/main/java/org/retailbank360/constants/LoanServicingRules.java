package org.retailbank360.constants;

/** Servicing thresholds for the scheduled loan jobs. */
public final class LoanServicingRules {

    /**
     * Days past the due date before a loan is written off as {@link LoanStatus#DEFAULTED}.
     *
     * <p>Ninety days is the conventional non-performing-asset boundary. The transition is one way and
     * final, so it is deliberately slow.</p>
     */
    public static final int DAYS_OVERDUE_BEFORE_DEFAULT = 90;

    /** How many instalments one automatic collection run will attempt, to bound a single sweep. */
    public static final int MAX_COLLECTIONS_PER_RUN = 500;

    private LoanServicingRules() {
    }
}

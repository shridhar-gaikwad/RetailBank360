package org.retailbank360.constants;

/** Lifecycle of a loan, which doubles as the state machine of the disbursement saga. */
public enum LoanStatus {

    /** Submitted and awaiting a decision. */
    APPLIED,

    /** Approved but not yet funded. */
    APPROVED,

    /** Declined; the reason is stored on the loan. Terminal. */
    REJECTED,

    /**
     * Funding in flight: the schedule exists and the ledger has been asked to credit the account,
     * but the outcome has not been confirmed yet. Recoverable, never terminal.
     */
    DISBURSING,

    /** Funded. Repayments are due. */
    DISBURSED,

    /** Fully repaid. Terminal. */
    CLOSED,

    /** Written off after prolonged non-payment. Terminal. */
    DEFAULTED
}

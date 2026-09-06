package org.retailbank360.constants;

/** State of a single instalment in the amortisation schedule. */
public enum RepaymentStatus {

    /** Not yet due, or due and not yet paid. */
    PENDING,

    /** Part of the instalment has been received. */
    PARTIALLY_PAID,

    /** Settled in full. */
    PAID,

    /** Past its due date and still unpaid. */
    OVERDUE
}

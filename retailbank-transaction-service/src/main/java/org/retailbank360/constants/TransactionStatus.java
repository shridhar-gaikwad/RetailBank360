package org.retailbank360.constants;

/** Lifecycle of a transaction record, which is also the state machine of the saga. */
public enum TransactionStatus {

    /** Recorded, but the ledger step has not been confirmed yet. */
    PENDING,

    /** The ledger accepted the movement. Terminal. */
    SUCCESS,

    /** The ledger rejected the movement, or the saga compensated it. Terminal. */
    FAILED,

    /** The movement was posted and later undone by a compensating entry. Terminal. */
    REVERSED
}

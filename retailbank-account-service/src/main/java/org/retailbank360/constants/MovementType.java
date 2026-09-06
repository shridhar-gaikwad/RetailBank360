package org.retailbank360.constants;

/** Why a ledger entry exists. Stored on every entry so a statement can be explained line by line. */
public enum MovementType {

    DEPOSIT,
    WITHDRAWAL,
    TRANSFER_OUT,
    TRANSFER_IN,
    LOAN_DISBURSEMENT,
    LOAN_REPAYMENT,
    FEE,
    INTEREST,
    /** Compensating entry that undoes an earlier movement during a saga rollback. */
    REVERSAL
}

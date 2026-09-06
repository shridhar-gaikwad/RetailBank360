package org.retailbank360.constants;

public enum AccountStatus {

    /** Fully operational. */
    ACTIVE,

    /** Dormant. Credits are accepted, debits are not. */
    INACTIVE,

    /** Frozen by compliance. No movement in either direction. */
    FROZEN,

    /** Closed for good. No movement, and the account cannot be reopened. */
    CLOSED
}

package org.retailbank360.constants;

/**
 * Know-Your-Customer state.
 *
 * <p>An account may only be opened for a {@link #VERIFIED} customer, which is the KYC validation the
 * requirement asks for at account opening.</p>
 */
public enum KycStatus {

    /** Documents captured but not yet checked. No account can be opened. */
    PENDING,

    /** Identity confirmed by a teller or an administrator. */
    VERIFIED,

    /** Verification failed; the reason is stored on the customer record. */
    REJECTED
}

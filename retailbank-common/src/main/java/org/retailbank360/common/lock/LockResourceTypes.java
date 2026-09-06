package org.retailbank360.common.lock;

/**
 * Lock key namespaces.
 *
 * <p>Kept in one place so the key a service takes and the key an operator looks up in the lock view
 * are guaranteed to be spelled the same way. Every value names a <em>row</em> or a well defined
 * business resource - never a table and never the database.</p>
 */
public final class LockResourceTypes {

    /** One bank account. Protects balance changes: deposit, withdrawal, both legs of a transfer. */
    public static final String ACCOUNT = "ACCOUNT";

    /** One loan. Protects approval, disbursement and repayment posting. */
    public static final String LOAN = "LOAN";

    /** One customer record. Protects KYC transitions and profile edits. */
    public static final String CUSTOMER = "CUSTOMER";

    /** One batch settlement / payout run, which touches many accounts under a single lock. */
    public static final String SETTLEMENT_BATCH = "SETTLEMENT_BATCH";

    private LockResourceTypes() {
    }
}

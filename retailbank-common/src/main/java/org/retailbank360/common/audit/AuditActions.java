package org.retailbank360.common.audit;

/** Canonical audit action names, so the admin audit view can filter on stable values. */
public final class AuditActions {

    public static final String CUSTOMER_CREATED = "CUSTOMER_CREATED";
    public static final String CUSTOMER_UPDATED = "CUSTOMER_UPDATED";
    public static final String CUSTOMER_DELETED = "CUSTOMER_DELETED";
    public static final String KYC_VERIFIED = "KYC_VERIFIED";
    public static final String KYC_REJECTED = "KYC_REJECTED";

    public static final String ACCOUNT_OPENED = "ACCOUNT_OPENED";
    public static final String ACCOUNT_UPDATED = "ACCOUNT_UPDATED";
    public static final String ACCOUNT_CLOSED = "ACCOUNT_CLOSED";
    public static final String ACCOUNT_STATUS_CHANGED = "ACCOUNT_STATUS_CHANGED";
    public static final String ACCOUNT_CREDITED = "ACCOUNT_CREDITED";
    public static final String ACCOUNT_DEBITED = "ACCOUNT_DEBITED";
    public static final String TRANSFER_COMPLETED = "TRANSFER_COMPLETED";
    public static final String TRANSFER_FAILED = "TRANSFER_FAILED";
    public static final String TRANSFER_REVERSED = "TRANSFER_REVERSED";

    public static final String TRANSACTION_INITIATED = "TRANSACTION_INITIATED";
    public static final String TRANSACTION_COMPLETED = "TRANSACTION_COMPLETED";
    public static final String TRANSACTION_FAILED = "TRANSACTION_FAILED";
    public static final String TRANSACTION_COMPENSATED = "TRANSACTION_COMPENSATED";

    public static final String LOAN_APPLIED = "LOAN_APPLIED";
    public static final String LOAN_APPROVED = "LOAN_APPROVED";
    public static final String LOAN_REJECTED = "LOAN_REJECTED";
    public static final String LOAN_DISBURSED = "LOAN_DISBURSED";
    public static final String LOAN_DISBURSEMENT_FAILED = "LOAN_DISBURSEMENT_FAILED";
    public static final String LOAN_REPAYMENT_POSTED = "LOAN_REPAYMENT_POSTED";
    public static final String LOAN_CLOSED = "LOAN_CLOSED";

    public static final String LOGIN_SUCCEEDED = "LOGIN_SUCCEEDED";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGIN_LOCKED_OUT = "LOGIN_LOCKED_OUT";
    public static final String MFA_CHALLENGED = "MFA_CHALLENGED";
    public static final String MFA_VERIFIED = "MFA_VERIFIED";
    public static final String MFA_ENABLED = "MFA_ENABLED";
    public static final String TOKEN_REFRESHED = "TOKEN_REFRESHED";
    public static final String LOGOUT = "LOGOUT";
    public static final String USER_REGISTERED = "USER_REGISTERED";

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";

    private AuditActions() {
    }
}

package org.retailbank360.common.constants;

/**
 * Canonical role names used across every RetailBank360 service.
 *
 * <p>Roles are carried in the {@code role} claim of the JWT issued by the auth-service and are
 * mapped to Spring Security authorities by prefixing them with {@code ROLE_}.</p>
 */
public final class Roles {

    /** End customer. May only see and operate on resources owned by their own customer id. */
    public static final String CUSTOMER = "CUSTOMER";

    /** Branch teller. May open accounts, run KYC and post deposits/withdrawals on behalf of a customer. */
    public static final String TELLER = "TELLER";

    /** Loan officer. May approve and disburse loans. */
    public static final String LOAN_OFFICER = "LOAN_OFFICER";

    /** Administrator. Full access including audit views and lock administration. */
    public static final String ADMIN = "ADMIN";

    /** Machine-to-machine principal used for internal service calls (never issued to a human). */
    public static final String SERVICE = "SERVICE";

    public static final String ROLE_PREFIX = "ROLE_";

    public static final String HAS_CUSTOMER = "hasRole('" + CUSTOMER + "')";
    public static final String HAS_TELLER = "hasRole('" + TELLER + "')";
    public static final String HAS_ADMIN = "hasRole('" + ADMIN + "')";
    public static final String HAS_SERVICE = "hasRole('" + SERVICE + "')";
    public static final String HAS_TELLER_OR_ADMIN = "hasAnyRole('" + TELLER + "','" + ADMIN + "')";
    public static final String HAS_LOAN_OFFICER_OR_ADMIN = "hasAnyRole('" + LOAN_OFFICER + "','" + ADMIN + "')";
    public static final String HAS_STAFF = "hasAnyRole('" + TELLER + "','" + LOAN_OFFICER + "','" + ADMIN + "')";
    public static final String HAS_STAFF_OR_SERVICE =
            "hasAnyRole('" + TELLER + "','" + LOAN_OFFICER + "','" + ADMIN + "','" + SERVICE + "')";

    private Roles() {
    }
}

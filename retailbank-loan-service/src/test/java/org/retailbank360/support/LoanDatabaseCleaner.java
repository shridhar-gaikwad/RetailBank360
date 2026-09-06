package org.retailbank360.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Resets the loan schema between tests, children before parents.
 *
 * <p>A static helper rather than a {@code @Component}: each service scopes its component scan to its
 * own production packages, and a test-only package has no business appearing in that list.</p>
 */
public final class LoanDatabaseCleaner {

    private static final List<String> TABLES = List.of(
            "loan_repayments",
            "loans",
            "resource_lock_audit",
            "resource_locks",
            "idempotency_records");

    private LoanDatabaseCleaner() {
    }

    public static void clean(JdbcTemplate jdbcTemplate) {
        for (String table : TABLES) {
            jdbcTemplate.execute("delete from " + table);
        }
    }
}

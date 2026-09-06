package org.retailbank360.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Resets the schema between tests.
 *
 * <p>{@code repository.deleteAll()} cannot be used for the ledger, the change history or the lock
 * audit: those entities refuse deletion through their JPA lifecycle callbacks, which is exactly the
 * immutability guarantee the production code relies on. A test must not be able to weaken it, so the
 * cleanup goes around JPA entirely with plain SQL instead.</p>
 *
 * <p>A static helper rather than a {@code @Component}: each service scopes its component scan to its
 * own production packages, and a test-only package has no business appearing in that list.</p>
 */
public final class DatabaseCleaner {

    /** Children before parents, so any future foreign key still deletes cleanly. */
    private static final List<String> TABLES = List.of(
            "ledger_entries",
            "account_change_history",
            "accounts",
            "resource_lock_audit",
            "resource_locks",
            "idempotency_records");

    private DatabaseCleaner() {
    }

    public static void clean(JdbcTemplate jdbcTemplate) {
        for (String table : TABLES) {
            jdbcTemplate.execute("delete from " + table);
        }
    }
}

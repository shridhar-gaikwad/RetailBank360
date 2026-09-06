package org.retailbank360.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.retailbank360.constants.AccountStatus;
import org.retailbank360.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for accounts.
 *
 * <p>Replaces the earlier stub implementation, which returned {@code null} and could not have
 * supported a balance at all.</p>
 *
 * <p>The {@code ForUpdate} finders issue {@code SELECT ... FOR UPDATE}: a row-level pessimistic
 * lock, held for the rest of the transaction. That is the granularity the requirement asks for -
 * one account, never the table and never the database - so two tellers working on two different
 * accounts never wait on each other.</p>
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    List<Account> findByCustomerId(Long customerId);

    List<Account> findByCustomerIdAndStatus(Long customerId, AccountStatus status);

    long countByCustomerIdAndStatus(Long customerId, AccountStatus status);

    /**
     * Reads one account with an exclusive row lock.
     *
     * <p>The lock timeout stops a caller from blocking forever behind a stuck transaction; the
     * database raises a lock-acquisition error instead, which the retry template turns into a bounded
     * retry and, ultimately, a clear 409 or 423 rather than a hung request thread.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select a from Account a where a.accountNumber = :accountNumber")
    Optional<Account> findByAccountNumberForUpdate(@Param("accountNumber") String accountNumber);

    /**
     * Locks several accounts in one statement, ordered by id.
     *
     * <p>Ordering matters: two transfers touching the same pair of accounts in opposite directions
     * would otherwise grab the rows in opposite orders and deadlock. Taking them in ascending id
     * order gives every transaction the same lock ordering, which prevents the cycle outright.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> lockAllByIdOrdered(@Param("ids") List<Long> ids);
}

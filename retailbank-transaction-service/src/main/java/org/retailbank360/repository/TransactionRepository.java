package org.retailbank360.repository;

import org.retailbank360.constants.TransactionStatus;
import org.retailbank360.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Persistence for transaction records. */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByTransactionRef(String transactionRef);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /** Everything that touched an account, in either direction, newest first. */
    @Query("""
            select t from Transaction t
             where t.fromAccountId = :accountId
                or t.toAccountId = :accountId
             order by t.createdAt desc
            """)
    Page<Transaction> findByAccount(@Param("accountId") Long accountId, Pageable pageable);

    Page<Transaction> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<Transaction> findByStatus(TransactionStatus status);

    /**
     * Transactions stuck in {@code PENDING} past a cutoff.
     *
     * <p>These are the saga's loose ends: the ledger call was made but its outcome was never
     * observed, typically because the caller died or the network dropped the response. The
     * reconciliation job asks account-service what actually happened and settles them.</p>
     */
    @Query("""
            select t from Transaction t
             where t.status = org.retailbank360.constants.TransactionStatus.PENDING
               and t.createdAt < :cutoff
             order by t.createdAt asc
            """)
    List<Transaction> findStalePending(@Param("cutoff") LocalDateTime cutoff);
}

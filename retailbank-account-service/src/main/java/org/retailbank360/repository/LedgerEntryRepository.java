package org.retailbank360.repository;

import org.retailbank360.entity.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Read access to the immutable ledger.
 *
 * <p>Only reads and inserts are exposed. There is no update or delete method by design: the entity
 * refuses those operations, and offering them here would invite someone to try.</p>
 */
@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    Optional<LedgerEntry> findByEntryRef(String entryRef);

    List<LedgerEntry> findByReferenceOrderByPostedAtAsc(String reference);

    List<LedgerEntry> findByOperationIdOrderByPostedAtAsc(String operationId);

    Page<LedgerEntry> findByAccountIdOrderByPostedAtDesc(Long accountId, Pageable pageable);

    /** Statement window, oldest first, so a running balance reads naturally. */
    @Query("""
            select e from LedgerEntry e
             where e.accountId = :accountId
               and e.postedAt >= :from
               and e.postedAt < :to
             order by e.postedAt asc, e.id asc
            """)
    List<LedgerEntry> findStatementEntries(@Param("accountId") Long accountId,
                                           @Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to);

    /** Balance carried into a statement period: the closing balance of the last entry before it. */
    @Query("""
            select e.balanceAfter from LedgerEntry e
             where e.accountId = :accountId
               and e.postedAt < :from
             order by e.postedAt desc, e.id desc
             limit 1
            """)
    Optional<BigDecimal> findOpeningBalance(@Param("accountId") Long accountId,
                                            @Param("from") LocalDateTime from);

    /** Sum of one direction inside a window, used for statement totals. */
    @Query("""
            select coalesce(sum(e.amount), 0) from LedgerEntry e
             where e.accountId = :accountId
               and e.direction = :direction
               and e.postedAt >= :from
               and e.postedAt < :to
            """)
    BigDecimal sumByDirection(@Param("accountId") Long accountId,
                              @Param("direction") org.retailbank360.constants.LedgerDirection direction,
                              @Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to);

    boolean existsByReferenceAndAccountId(String reference, Long accountId);

    /**
     * True when a compensating entry already points at any of these entries.
     *
     * <p>A reversal is written under its own reference, so it never appears when searching by the
     * original reference. Whether something has been reversed is answered by the back-link, not by
     * the reference.</p>
     */
    boolean existsByReversesEntryRefIn(java.util.Collection<String> originalEntryRefs);
}

package org.retailbank360.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.retailbank360.constants.LoanStatus;
import org.retailbank360.entity.Loan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Persistence for loans. */
@Repository
public interface LoanRepository extends JpaRepository<Loan, Long> {

    Optional<Loan> findByLoanRef(String loanRef);

    List<Loan> findByCustomerId(Long customerId);

    List<Loan> findByCustomerIdAndStatusIn(Long customerId, List<LoanStatus> statuses);

    List<Loan> findByStatus(LoanStatus status);

    /**
     * Row-locked read used by disbursement and repayment posting.
     *
     * <p>Two loan officers clicking "disburse" at the same moment would otherwise both read the loan
     * as APPROVED and both fund it. The lock serialises them, and the status check inside then makes
     * the second one a no-op.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select l from Loan l where l.id = :id")
    Optional<Loan> findByIdForUpdate(@Param("id") Long id);

    /**
     * Loans stuck mid-disbursement.
     *
     * <p>DISBURSING means the ledger was asked to credit the account but the answer was never seen.
     * The recovery job asks the ledger what happened and either completes or rolls the loan back.</p>
     */
    @Query("""
            select l from Loan l
             where l.status = org.retailbank360.constants.LoanStatus.DISBURSING
               and l.disbursementStartedAt < :cutoff
            """)
    List<Loan> findStuckDisbursements(@Param("cutoff") LocalDateTime cutoff);
}

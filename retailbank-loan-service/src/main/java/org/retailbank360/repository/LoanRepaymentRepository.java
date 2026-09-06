package org.retailbank360.repository;

import org.retailbank360.constants.RepaymentStatus;
import org.retailbank360.entity.LoanRepayment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/** Persistence for the amortisation schedule. */
@Repository
public interface LoanRepaymentRepository extends JpaRepository<LoanRepayment, Long> {

    List<LoanRepayment> findByLoanIdOrderByInstallmentNumberAsc(Long loanId);

    /** Unpaid instalments, oldest first: the order a payment is applied in. */
    @Query("""
            select r from LoanRepayment r
             where r.loan.id = :loanId
               and r.status <> org.retailbank360.constants.RepaymentStatus.PAID
             order by r.installmentNumber asc
            """)
    List<LoanRepayment> findOutstanding(@Param("loanId") Long loanId);

    /**
     * Instalments that are due and not yet settled, oldest first.
     *
     * <p>Drives automatic collection. Restricted to loans that are actually live, so a closed or
     * defaulted loan is never debited.</p>
     *
     * <p>The loan is fetched eagerly: the sweep reads it outside a transaction, and it would
     * otherwise hit a lazy proxy with no session - as well as issuing one query per row.</p>
     */
    @Query("""
            select r from LoanRepayment r
             join fetch r.loan loan
             where r.dueDate <= :today
               and r.status <> org.retailbank360.constants.RepaymentStatus.PAID
               and loan.status = org.retailbank360.constants.LoanStatus.DISBURSED
             order by r.dueDate asc, r.installmentNumber asc
            """)
    List<LoanRepayment> findCollectible(@Param("today") LocalDate today, Pageable pageable);

    /** Oldest unpaid instalment per loan, used to decide whether a loan has defaulted. */
    @Query("""
            select r from LoanRepayment r
             join fetch r.loan loan
             where r.dueDate < :cutoff
               and r.status <> org.retailbank360.constants.RepaymentStatus.PAID
               and loan.status = org.retailbank360.constants.LoanStatus.DISBURSED
            """)
    List<LoanRepayment> findLongOverdue(@Param("cutoff") LocalDate cutoff);

    /** Instalments past their due date and still unpaid, for the overdue marking job. */
    @Query("""
            select r from LoanRepayment r
             where r.dueDate < :today
               and r.status in :openStatuses
            """)
    List<LoanRepayment> findPastDue(@Param("today") LocalDate today,
                                    @Param("openStatuses") List<RepaymentStatus> openStatuses);
}

package org.retailbank360.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.retailbank360.constants.LoanStatus;
import org.retailbank360.constants.LoanType;
import org.retailbank360.entity.Loan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Loan as returned by the API. Also the payload replayed against an idempotency key. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoanResponse {

    private Long id;

    private String loanRef;

    private Long customerId;

    private Long disbursementAccountId;

    private LoanType loanType;

    private BigDecimal principal;

    private BigDecimal interestRate;

    private Integer tenureMonths;

    private BigDecimal emiAmount;

    private BigDecimal outstandingPrincipal;

    private BigDecimal totalInterest;

    private BigDecimal totalRepaid;

    private String currency;

    private LoanStatus status;

    private String decisionReason;

    private String decidedBy;

    private LocalDate firstDueDate;

    private LocalDateTime appliedAt;

    private LocalDateTime decidedAt;

    private LocalDateTime disbursedAt;

    private String disbursementReference;

    private LocalDateTime closedAt;

    public static LoanResponse from(Loan loan) {
        return LoanResponse.builder()
                .id(loan.getId())
                .loanRef(loan.getLoanRef())
                .customerId(loan.getCustomerId())
                .disbursementAccountId(loan.getDisbursementAccountId())
                .loanType(loan.getLoanType())
                .principal(loan.getPrincipal())
                .interestRate(loan.getInterestRate())
                .tenureMonths(loan.getTenureMonths())
                .emiAmount(loan.getEmiAmount())
                .outstandingPrincipal(loan.getOutstandingPrincipal())
                .totalInterest(loan.getTotalInterest())
                .totalRepaid(loan.getTotalRepaid())
                .currency(loan.getCurrency())
                .status(loan.getStatus())
                .decisionReason(loan.getDecisionReason())
                .decidedBy(loan.getDecidedBy())
                .firstDueDate(loan.getFirstDueDate())
                .appliedAt(loan.getAppliedAt())
                .decidedAt(loan.getDecidedAt())
                .disbursedAt(loan.getDisbursedAt())
                .disbursementReference(loan.getDisbursementReference())
                .closedAt(loan.getClosedAt())
                .build();
    }
}

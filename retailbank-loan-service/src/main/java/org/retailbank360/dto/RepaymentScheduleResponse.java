package org.retailbank360.dto;

import lombok.Builder;
import lombok.Data;
import org.retailbank360.constants.RepaymentStatus;
import org.retailbank360.entity.LoanRepayment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One instalment of an amortisation schedule, as shown to the customer. */
@Data
@Builder
public class RepaymentScheduleResponse {

    private Integer installmentNumber;

    private LocalDate dueDate;

    private BigDecimal emiAmount;

    private BigDecimal principalComponent;

    private BigDecimal interestComponent;

    private BigDecimal outstandingAfter;

    private BigDecimal paidAmount;

    private BigDecimal remaining;

    private RepaymentStatus status;

    private LocalDateTime paidAt;

    private String paymentReference;

    public static RepaymentScheduleResponse from(LoanRepayment repayment) {
        return RepaymentScheduleResponse.builder()
                .installmentNumber(repayment.getInstallmentNumber())
                .dueDate(repayment.getDueDate())
                .emiAmount(repayment.getEmiAmount())
                .principalComponent(repayment.getPrincipalComponent())
                .interestComponent(repayment.getInterestComponent())
                .outstandingAfter(repayment.getOutstandingAfter())
                .paidAmount(repayment.getPaidAmount())
                .remaining(repayment.getRemaining())
                .status(repayment.getStatus())
                .paidAt(repayment.getPaidAt())
                .paymentReference(repayment.getPaymentReference())
                .build();
    }
}

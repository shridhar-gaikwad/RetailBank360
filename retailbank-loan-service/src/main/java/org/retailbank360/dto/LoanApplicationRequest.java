package org.retailbank360.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.retailbank360.constants.LoanType;

import java.math.BigDecimal;

/** Loan application. Eligibility is scored server side; nothing here can pre-approve the loan. */
@Data
public class LoanApplicationRequest {

    @NotNull(message = "Customer id must not be null")
    private Long customerId;

    /** Account the principal is credited to, and repayments are collected from. */
    @NotNull(message = "Disbursement account id must not be null")
    private Long disbursementAccountId;

    @NotNull(message = "Loan type must not be null")
    private LoanType loanType;

    @NotNull(message = "Principal must not be null")
    @DecimalMin(value = "1000.00", message = "The smallest loan we write is 1000")
    @Digits(integer = 17, fraction = 2, message = "Amount must have at most two decimal places")
    private BigDecimal principal;

    @NotNull(message = "Tenure must not be null")
    @Min(value = 3, message = "Tenure must be at least 3 months")
    @Max(value = 360, message = "Tenure must not exceed 360 months")
    private Integer tenureMonths;

    /** Optional override within the product band; the product default applies when omitted. */
    @DecimalMin(value = "0.100", message = "Interest rate must be positive")
    @DecimalMax(value = "36.000", message = "Interest rate must not exceed 36%")
    private BigDecimal interestRate;

    @Size(max = 3, min = 3, message = "Currency must be a 3-letter code")
    private String currency;

    @Size(max = 255, message = "Purpose must not exceed 255 characters")
    private String purpose;
}

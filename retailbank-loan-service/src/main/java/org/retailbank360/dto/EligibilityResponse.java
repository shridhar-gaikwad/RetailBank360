package org.retailbank360.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Outcome of the eligibility check.
 *
 * <p>Returned both from the standalone pre-check endpoint and, when an application is declined, as
 * the explanation on the rejection - so a customer is always told which rule they missed.</p>
 */
@Data
@Builder
public class EligibilityResponse {

    private boolean eligible;

    private Integer creditScore;

    private BigDecimal monthlyIncome;

    private BigDecimal proposedEmi;

    /** Total monthly obligation as a share of income, the classic FOIR ratio. */
    private BigDecimal debtToIncomeRatio;

    private BigDecimal maximumEligibleAmount;

    /** Every rule that failed, so the applicant sees all of them rather than one at a time. */
    private List<String> reasons;
}

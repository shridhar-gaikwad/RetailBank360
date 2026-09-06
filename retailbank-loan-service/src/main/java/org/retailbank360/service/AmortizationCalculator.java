package org.retailbank360.service;

import org.retailbank360.common.util.MoneyUtil;
import org.retailbank360.entity.Loan;
import org.retailbank360.entity.LoanRepayment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Equated monthly instalment and the amortisation schedule behind it.
 *
 * <p>Uses the standard annuity formula {@code EMI = P*r*(1+r)^n / ((1+r)^n - 1)} with
 * {@code r = annual rate / 12 / 100}, computed in {@link BigDecimal} throughout.</p>
 *
 * <p>Rounding is handled explicitly rather than left to chance: each instalment is rounded to two
 * decimals, and the <em>final</em> instalment absorbs the accumulated rounding difference so the
 * schedule sums exactly to principal plus interest. Without that adjustment a loan would finish a
 * few paise short or over, and the outstanding balance would never reach exactly zero.</p>
 */
@Component
public class AmortizationCalculator {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal MONTHS_PER_YEAR = new BigDecimal("12");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * Monthly instalment for a principal, an annual rate and a tenure.
     *
     * <p>A zero rate degenerates to a simple division, which the annuity formula cannot express
     * because it divides by {@code (1+r)^n - 1}.</p>
     */
    public BigDecimal calculateEmi(BigDecimal principal, BigDecimal annualRatePercent, int tenureMonths) {
        if (tenureMonths <= 0) {
            throw new IllegalArgumentException("Tenure must be at least one month");
        }
        BigDecimal monthlyRate = monthlyRate(annualRatePercent);
        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return MoneyUtil.normalize(principal.divide(BigDecimal.valueOf(tenureMonths), MC));
        }

        BigDecimal growth = BigDecimal.ONE.add(monthlyRate).pow(tenureMonths, MC);
        BigDecimal numerator = principal.multiply(monthlyRate, MC).multiply(growth, MC);
        BigDecimal denominator = growth.subtract(BigDecimal.ONE);
        return MoneyUtil.normalize(numerator.divide(denominator, MC));
    }

    /** Total interest paid across the whole tenure. */
    public BigDecimal calculateTotalInterest(BigDecimal principal, BigDecimal emi, int tenureMonths) {
        return MoneyUtil.normalize(emi.multiply(BigDecimal.valueOf(tenureMonths)).subtract(principal));
    }

    /**
     * Builds the full schedule, one row per month starting from {@code firstDueDate}.
     *
     * <p>Interest each month is charged on the balance still outstanding, and the remainder of the
     * instalment reduces the principal. The last row is adjusted so the balance lands on exactly
     * zero.</p>
     */
    public List<LoanRepayment> buildSchedule(Loan loan, LocalDate firstDueDate) {
        BigDecimal monthlyRate = monthlyRate(loan.getInterestRate());
        BigDecimal emi = loan.getEmiAmount();
        BigDecimal outstanding = loan.getPrincipal();

        List<LoanRepayment> schedule = new ArrayList<>(loan.getTenureMonths());

        for (int installment = 1; installment <= loan.getTenureMonths(); installment++) {
            BigDecimal interest = MoneyUtil.normalize(outstanding.multiply(monthlyRate, MC));
            BigDecimal principalPart = MoneyUtil.normalize(emi.subtract(interest));
            BigDecimal instalmentAmount = emi;

            if (installment == loan.getTenureMonths()) {
                // Final instalment: clear whatever is left, absorbing all accumulated rounding.
                principalPart = outstanding;
                instalmentAmount = MoneyUtil.normalize(principalPart.add(interest));
            } else if (principalPart.compareTo(outstanding) > 0) {
                principalPart = outstanding;
                instalmentAmount = MoneyUtil.normalize(principalPart.add(interest));
            }

            outstanding = MoneyUtil.normalize(outstanding.subtract(principalPart));

            LoanRepayment row = new LoanRepayment();
            row.setInstallmentNumber(installment);
            row.setDueDate(firstDueDate.plusMonths(installment - 1L));
            row.setEmiAmount(instalmentAmount);
            row.setPrincipalComponent(principalPart);
            row.setInterestComponent(interest);
            row.setOutstandingAfter(outstanding);
            schedule.add(row);
        }
        return schedule;
    }

    private static BigDecimal monthlyRate(BigDecimal annualRatePercent) {
        if (annualRatePercent == null) {
            return BigDecimal.ZERO;
        }
        return annualRatePercent.divide(HUNDRED, MC).divide(MONTHS_PER_YEAR, MC);
    }
}

package org.retailbank360.service;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.client.dto.CustomerProfile;
import org.retailbank360.constants.LoanStatus;
import org.retailbank360.dto.EligibilityResponse;
import org.retailbank360.entity.Loan;
import org.retailbank360.repository.LoanRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

/**
 * Consumer lending rules.
 *
 * <p>Every rule is evaluated even after one has already failed, so an applicant is told everything
 * that stands in the way rather than being sent round a loop discovering one problem at a time.</p>
 *
 * <p>The credit score is a stored attribute on the customer record - the placeholder the requirement
 * calls for. Swapping it for a bureau call means changing where the number comes from, not how it is
 * used.</p>
 */
@Slf4j
@Service
public class LoanEligibilityService {

    /** Lowest bureau score we will lend against. */
    static final int MINIMUM_CREDIT_SCORE = 650;

    /** Maximum share of monthly income that may go to loan instalments (FOIR). */
    static final BigDecimal MAX_DEBT_TO_INCOME = new BigDecimal("0.50");

    /** Loan may not exceed this multiple of annual income. */
    static final BigDecimal MAX_PRINCIPAL_TO_ANNUAL_INCOME = new BigDecimal("5");

    static final int MINIMUM_AGE = 21;

    /** The loan must be fully repaid before the applicant reaches this age. */
    static final int MAXIMUM_AGE_AT_MATURITY = 65;

    private static final BigDecimal TWELVE = new BigDecimal("12");

    private final LoanRepository loanRepository;
    private final AmortizationCalculator amortizationCalculator;

    public LoanEligibilityService(LoanRepository loanRepository, AmortizationCalculator amortizationCalculator) {
        this.loanRepository = loanRepository;
        this.amortizationCalculator = amortizationCalculator;
    }

    /**
     * Scores an application.
     *
     * @param profile   customer profile from customer-service
     * @param principal requested amount
     * @param annualRatePercent rate the loan would carry
     * @param tenureMonths      requested tenure
     */
    public EligibilityResponse evaluate(CustomerProfile profile, BigDecimal principal,
                                        BigDecimal annualRatePercent, int tenureMonths) {

        List<String> reasons = new ArrayList<>();
        BigDecimal monthlyIncome = monthlyIncome(profile.getAnnualIncome());
        BigDecimal proposedEmi = amortizationCalculator.calculateEmi(principal, annualRatePercent, tenureMonths);

        if (!profile.isKycVerified()) {
            reasons.add("KYC is " + profile.getKycStatus() + "; it must be VERIFIED before a loan can be granted");
        }
        if (!profile.isActive()) {
            reasons.add("The customer record is " + profile.getStatus() + " and not eligible to borrow");
        }
        if (profile.getCreditScore() == null) {
            reasons.add("No credit score is on file for this customer");
        } else if (profile.getCreditScore() < MINIMUM_CREDIT_SCORE) {
            reasons.add("Credit score " + profile.getCreditScore()
                    + " is below the minimum of " + MINIMUM_CREDIT_SCORE);
        }
        if (profile.getAnnualIncome() == null || profile.getAnnualIncome().compareTo(BigDecimal.ZERO) <= 0) {
            reasons.add("No annual income is recorded for this customer");
        }

        BigDecimal existingEmi = existingMonthlyObligations(profile.getCustomerId());
        BigDecimal totalEmi = existingEmi.add(proposedEmi);
        BigDecimal debtToIncome = monthlyIncome.compareTo(BigDecimal.ZERO) > 0
                ? totalEmi.divide(monthlyIncome, 4, RoundingMode.HALF_UP)
                : BigDecimal.ONE;

        if (monthlyIncome.compareTo(BigDecimal.ZERO) > 0 && debtToIncome.compareTo(MAX_DEBT_TO_INCOME) > 0) {
            reasons.add("Total instalments would be " + percent(debtToIncome) + "% of monthly income,"
                    + " above the " + percent(MAX_DEBT_TO_INCOME) + "% ceiling"
                    + (existingEmi.compareTo(BigDecimal.ZERO) > 0
                    ? " (existing loans already account for " + existingEmi + " a month)" : ""));
        }
        if (profile.getAnnualIncome() != null
                && principal.compareTo(profile.getAnnualIncome().multiply(MAX_PRINCIPAL_TO_ANNUAL_INCOME)) > 0) {
            reasons.add("The requested amount exceeds " + MAX_PRINCIPAL_TO_ANNUAL_INCOME
                    + " times annual income");
        }

        checkAge(profile.getDateOfBirth(), tenureMonths, reasons);

        if (!loanRepository.findByCustomerIdAndStatusIn(profile.getCustomerId(),
                List.of(LoanStatus.DEFAULTED)).isEmpty()) {
            reasons.add("The customer has a loan marked DEFAULTED");
        }

        return EligibilityResponse.builder()
                .eligible(reasons.isEmpty())
                .creditScore(profile.getCreditScore())
                .monthlyIncome(monthlyIncome)
                .proposedEmi(proposedEmi)
                .debtToIncomeRatio(debtToIncome)
                .maximumEligibleAmount(maximumEligibleAmount(monthlyIncome, existingEmi,
                        annualRatePercent, tenureMonths))
                .reasons(reasons)
                .build();
    }

    /**
     * Largest principal this applicant could support at the given rate and tenure.
     *
     * <p>Derived by inverting the EMI formula against the instalment head-room left once existing
     * obligations are taken off the FOIR ceiling, so a declined applicant can be told what they
     * <em>could</em> borrow instead of just being refused.</p>
     */
    private BigDecimal maximumEligibleAmount(BigDecimal monthlyIncome, BigDecimal existingEmi,
                                             BigDecimal annualRatePercent, int tenureMonths) {
        BigDecimal affordableEmi = monthlyIncome.multiply(MAX_DEBT_TO_INCOME).subtract(existingEmi);
        if (affordableEmi.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        // EMI is linear in principal, so one probe is enough to scale up to the affordable amount.
        BigDecimal probePrincipal = new BigDecimal("100000");
        BigDecimal probeEmi = amortizationCalculator.calculateEmi(probePrincipal, annualRatePercent, tenureMonths);
        if (probeEmi.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return affordableEmi.multiply(probePrincipal)
                .divide(probeEmi, 2, RoundingMode.DOWN);
    }

    /** Sum of the instalments the customer is already committed to. */
    private BigDecimal existingMonthlyObligations(Long customerId) {
        return loanRepository.findByCustomerIdAndStatusIn(customerId,
                        List.of(LoanStatus.DISBURSED, LoanStatus.DISBURSING))
                .stream()
                .map(Loan::getEmiAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void checkAge(LocalDate dateOfBirth, int tenureMonths, List<String> reasons) {
        if (dateOfBirth == null) {
            reasons.add("No date of birth is on file, so age eligibility cannot be assessed");
            return;
        }
        int age = Period.between(dateOfBirth, LocalDate.now()).getYears();
        if (age < MINIMUM_AGE) {
            reasons.add("The applicant is " + age + "; the minimum borrowing age is " + MINIMUM_AGE);
        }
        int ageAtMaturity = Period.between(dateOfBirth, LocalDate.now().plusMonths(tenureMonths)).getYears();
        if (ageAtMaturity > MAXIMUM_AGE_AT_MATURITY) {
            reasons.add("The loan would mature at age " + ageAtMaturity
                    + ", beyond the limit of " + MAXIMUM_AGE_AT_MATURITY);
        }
    }

    private static BigDecimal monthlyIncome(BigDecimal annualIncome) {
        return annualIncome == null
                ? BigDecimal.ZERO
                : annualIncome.divide(TWELVE, 2, RoundingMode.HALF_UP);
    }

    private static String percent(BigDecimal ratio) {
        return ratio.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}

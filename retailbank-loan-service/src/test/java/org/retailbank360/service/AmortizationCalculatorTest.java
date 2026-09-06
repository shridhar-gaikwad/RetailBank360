package org.retailbank360.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.retailbank360.constants.LoanType;
import org.retailbank360.entity.Loan;
import org.retailbank360.entity.LoanRepayment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the EMI formula and the amortisation schedule.
 *
 * <p>The important assertion is not that one EMI figure is right, but that the schedule <em>closes</em>:
 * the principal components must sum to exactly the principal, and the final outstanding balance must
 * be exactly zero. Getting that wrong leaves loans that never quite finish.</p>
 */
class AmortizationCalculatorTest {

    private final AmortizationCalculator calculator = new AmortizationCalculator();

    @Test
    @DisplayName("EMI matches the standard annuity formula")
    void computesEmi() {
        // 100,000 over 12 months at 12% a year is a textbook case: 8,884.88 a month.
        BigDecimal emi = calculator.calculateEmi(new BigDecimal("100000"), new BigDecimal("12.0"), 12);

        assertThat(emi).isEqualByComparingTo("8884.88");
    }

    @Test
    @DisplayName("An interest-free loan divides evenly, without dividing by zero")
    void handlesAZeroRate() {
        assertThat(calculator.calculateEmi(new BigDecimal("12000"), BigDecimal.ZERO, 12))
                .isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("A tenure of zero months is rejected")
    void rejectsAnEmptyTenure() {
        assertThatThrownBy(() -> calculator.calculateEmi(new BigDecimal("1000"), new BigDecimal("10"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Total interest is the sum of the instalments less the principal")
    void computesTotalInterest() {
        BigDecimal emi = calculator.calculateEmi(new BigDecimal("100000"), new BigDecimal("12.0"), 12);

        assertThat(calculator.calculateTotalInterest(new BigDecimal("100000"), emi, 12))
                .isEqualByComparingTo("6618.56");
    }

    @Test
    @DisplayName("The schedule closes exactly: principal sums to the loan and the balance reaches zero")
    void buildsAClosingSchedule() {
        Loan loan = loan("100000", "12.0", 12);
        LocalDate firstDue = LocalDate.of(2026, 1, 15);

        List<LoanRepayment> schedule = calculator.buildSchedule(loan, firstDue);

        assertThat(schedule).hasSize(12);
        assertThat(schedule.get(0).getDueDate()).isEqualTo(firstDue);
        assertThat(schedule.get(11).getDueDate()).isEqualTo(LocalDate.of(2026, 12, 15));

        BigDecimal principalTotal = schedule.stream()
                .map(LoanRepayment::getPrincipalComponent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(principalTotal)
                .as("every rupee of principal is scheduled exactly once")
                .isEqualByComparingTo("100000.00");
        assertThat(schedule.get(11).getOutstandingAfter())
                .as("the final instalment absorbs the rounding and clears the balance")
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Interest falls and principal rises across the schedule")
    void amortisesInterestBeforePrincipal() {
        List<LoanRepayment> schedule = calculator.buildSchedule(loan("100000", "12.0", 12),
                LocalDate.of(2026, 1, 15));

        LoanRepayment first = schedule.get(0);
        LoanRepayment last = schedule.get(11);

        assertThat(first.getInterestComponent()).isGreaterThan(last.getInterestComponent());
        assertThat(first.getPrincipalComponent()).isLessThan(last.getPrincipalComponent());
        assertThat(first.getInterestComponent())
                .as("first month's interest is one month at 1% on the full principal")
                .isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("An awkward principal and tenure still closes on zero")
    void handlesRoundingUnfriendlyInputs() {
        // 33,333.33 over 7 months at 13.75% divides into nothing round at all, which is exactly why
        // the final instalment has to absorb the accumulated rounding.
        Loan loan = loan("33333.33", "13.75", 7);
        List<LoanRepayment> schedule = calculator.buildSchedule(loan, LocalDate.of(2026, 3, 1));

        BigDecimal principalTotal = schedule.stream()
                .map(LoanRepayment::getPrincipalComponent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(principalTotal).isEqualByComparingTo("33333.33");
        assertThat(schedule.get(schedule.size() - 1).getOutstandingAfter()).isEqualByComparingTo("0.00");
        assertThat(schedule).allSatisfy(instalment ->
                assertThat(instalment.getEmiAmount()).isEqualByComparingTo(
                        instalment.getPrincipalComponent().add(instalment.getInterestComponent())));
    }

    private Loan loan(String principal, String rate, int tenureMonths) {
        Loan loan = new Loan();
        loan.setLoanType(LoanType.PERSONAL);
        loan.setPrincipal(new BigDecimal(principal));
        loan.setInterestRate(new BigDecimal(rate));
        loan.setTenureMonths(tenureMonths);
        loan.setEmiAmount(calculator.calculateEmi(new BigDecimal(principal), new BigDecimal(rate), tenureMonths));
        return loan;
    }
}

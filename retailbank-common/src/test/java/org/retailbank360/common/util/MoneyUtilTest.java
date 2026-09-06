package org.retailbank360.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the money helpers every amount in the system passes through. */
class MoneyUtilTest {

    @Test
    @DisplayName("Amounts are normalised to two decimals with half-up rounding")
    void normalisesScaleAndRounding() {
        assertThat(MoneyUtil.normalize(new BigDecimal("10.005"))).isEqualByComparingTo("10.01");
        assertThat(MoneyUtil.normalize(new BigDecimal("10.004"))).isEqualByComparingTo("10.00");
        assertThat(MoneyUtil.normalize(new BigDecimal("10"))).hasToString("10.00");
        assertThat(MoneyUtil.normalize(null)).isNull();
    }

    @Test
    @DisplayName("A null amount reads as zero rather than blowing up a calculation")
    void treatsNullAsZero() {
        assertThat(MoneyUtil.nullSafe(null)).isEqualByComparingTo("0.00");
        assertThat(MoneyUtil.nullSafe(new BigDecimal("5.5"))).isEqualByComparingTo("5.50");
    }

    @Test
    @DisplayName("Sign and comparison helpers ignore scale")
    void comparesByValueNotScale() {
        assertThat(MoneyUtil.isPositive(new BigDecimal("0.01"))).isTrue();
        assertThat(MoneyUtil.isPositive(BigDecimal.ZERO)).isFalse();
        assertThat(MoneyUtil.isPositive(null)).isFalse();
        assertThat(MoneyUtil.isNegative(new BigDecimal("-0.01"))).isTrue();

        // 100.00 and 100 are the same amount even though equals() would disagree.
        assertThat(MoneyUtil.lt(new BigDecimal("100.00"), new BigDecimal("100"))).isFalse();
        assertThat(MoneyUtil.gt(new BigDecimal("100.01"), new BigDecimal("100"))).isTrue();
    }
}

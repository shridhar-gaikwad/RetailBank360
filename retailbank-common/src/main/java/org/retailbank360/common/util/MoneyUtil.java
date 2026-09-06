package org.retailbank360.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money helpers. All monetary amounts in RetailBank360 are {@link BigDecimal} with scale 2 and
 * {@link RoundingMode#HALF_UP} rounding, never {@code double}, which cannot represent currency
 * exactly.
 */
public final class MoneyUtil {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private MoneyUtil() {
    }

    public static BigDecimal normalize(BigDecimal amount) {
        return amount == null ? null : amount.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal nullSafe(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO.setScale(SCALE, ROUNDING) : normalize(amount);
    }

    public static boolean isPositive(BigDecimal amount) {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public static boolean isNegative(BigDecimal amount) {
        return amount != null && amount.compareTo(BigDecimal.ZERO) < 0;
    }

    /** {@code true} when {@code a} is strictly less than {@code b}. */
    public static boolean lt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) < 0;
    }

    /** {@code true} when {@code a} is strictly greater than {@code b}. */
    public static boolean gt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0;
    }
}

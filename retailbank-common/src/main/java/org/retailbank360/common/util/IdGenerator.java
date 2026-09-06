package org.retailbank360.common.util;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Reference/number generators shared by the services. */
public final class IdGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DIGITS = "0123456789";

    private IdGenerator() {
    }

    /** Human friendly business reference, e.g. {@code TXN-20260903-8F3A1C6D}. */
    public static String reference(String prefix) {
        return prefix + "-" + LocalDate.now().format(DATE) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    /** Opaque token used as a lock owner id or an idempotency fallback. */
    public static String token() {
        return UUID.randomUUID().toString();
    }

    /** Numeric identifier of the requested length, e.g. an account number. */
    public static String numeric(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        }
        return sb.toString();
    }
}

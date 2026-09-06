package org.retailbank360.common.util;

/**
 * Masks sensitive values before they leave the service boundary (API responses and log lines).
 *
 * <p>Data is stored encrypted at rest; masking is the second half of the requirement, because a
 * decrypted value must never reach a UI or a log file in full.</p>
 */
public final class MaskingUtil {

    private static final String MASK_CHAR = "X";

    private MaskingUtil() {
    }

    /** {@code 100200300400} becomes {@code XXXXXXXX0400}. */
    public static String maskAccountNumber(String accountNumber) {
        return maskAllButLast(accountNumber, 4);
    }

    /** {@code ABCDE1234F} becomes {@code XXXXXX234F}. */
    public static String maskPan(String pan) {
        return maskAllButLast(pan, 4);
    }

    /** {@code 987654321012} becomes {@code XXXXXXXX1012}. */
    public static String maskAadhaar(String aadhaar) {
        return maskAllButLast(aadhaar, 4);
    }

    /** {@code 9876543210} becomes {@code XXXXXX3210}. */
    public static String maskPhone(String phone) {
        return maskAllButLast(phone, 4);
    }

    /** {@code john.doe@example.com} becomes {@code jo***@example.com}. */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return maskAllButLast(email, 0);
        }
        String local = email.substring(0, at);
        String keep = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return keep + "***" + email.substring(at);
    }

    /** Keeps the trailing {@code visible} characters and replaces everything before them. */
    public static String maskAllButLast(String value, int visible) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= visible) {
            return MASK_CHAR.repeat(trimmed.length());
        }
        return MASK_CHAR.repeat(trimmed.length() - visible) + trimmed.substring(trimmed.length() - visible);
    }
}

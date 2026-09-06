package org.retailbank360.service;

import org.retailbank360.constants.AuthConstants;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Time-based one-time passwords (RFC 6238) for the optional second login factor.
 *
 * <p>Implemented against the JDK crypto provider and a small Base32 codec rather than pulling in a
 * TOTP library: the algorithm is thirty lines, and the requirement is explicit about not adding
 * dependencies that are not needed. Codes are compatible with Google Authenticator, Authy, Microsoft
 * Authenticator and anything else that speaks the {@code otpauth://} URI format.</p>
 */
@Service
public class TotpService {

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int SECRET_BYTES = 20;
    private static final int[] DIGIT_DIVISORS = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000};

    private final SecureRandom random = new SecureRandom();

    /** Fresh 160-bit shared secret, Base32 encoded for typing into an authenticator app. */
    public String generateSecret() {
        byte[] buffer = new byte[SECRET_BYTES];
        random.nextBytes(buffer);
        return base32Encode(buffer);
    }

    /** Enrolment URI an authenticator app can read from a QR code. */
    public String buildOtpAuthUri(String issuer, String username, String secret) {
        String label = URLEncoder.encode(issuer + ":" + username, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + URLEncoder.encode(issuer, StandardCharsets.UTF_8)
                + "&algorithm=SHA1"
                + "&digits=" + AuthConstants.TOTP_DIGITS
                + "&period=" + AuthConstants.TOTP_TIME_STEP_SECONDS;
    }

    /**
     * Checks a submitted code against the current time step and one step either side, which absorbs
     * ordinary clock drift between the phone and the server.
     *
     * <p>The comparison is constant time, so a caller cannot learn a correct prefix by timing.</p>
     */
    public boolean verify(String secret, String code) {
        if (secret == null || secret.isBlank() || code == null || code.length() != AuthConstants.TOTP_DIGITS) {
            return false;
        }
        long currentStep = Instant.now().getEpochSecond() / AuthConstants.TOTP_TIME_STEP_SECONDS;
        int drift = AuthConstants.TOTP_ALLOWED_DRIFT_STEPS;

        for (long step = currentStep - drift; step <= currentStep + drift; step++) {
            if (constantTimeEquals(generateCode(secret, step), code)) {
                return true;
            }
        }
        return false;
    }

    /** Code for the current time step. Exposed so tests can drive a full MFA login. */
    public String currentCode(String secret) {
        return generateCode(secret, Instant.now().getEpochSecond() / AuthConstants.TOTP_TIME_STEP_SECONDS);
    }

    private String generateCode(String base32Secret, long timeStep) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(base32Decode(base32Secret), HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(timeStep).array());

            // RFC 4226 dynamic truncation: the low nibble of the last byte picks the 4-byte window.
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int otp = binary % DIGIT_DIVISORS[AuthConstants.TOTP_DIGITS];
            return String.format("%0" + AuthConstants.TOTP_DIGITS + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to compute a TOTP code", e);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    static String base32Encode(byte[] data) {
        StringBuilder encoded = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                encoded.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            encoded.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return encoded.toString();
    }

    static byte[] base32Decode(String encoded) {
        String normalized = encoded.trim().replace("=", "").toUpperCase();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (char c : normalized.toCharArray()) {
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Not a valid Base32 secret");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }
}

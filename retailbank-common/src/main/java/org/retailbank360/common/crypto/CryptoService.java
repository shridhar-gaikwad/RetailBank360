package org.retailbank360.common.crypto;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.config.CryptoProperties;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Encrypts personally identifiable information at rest with AES-256-GCM, and derives keyed blind
 * indexes so an encrypted column can still be looked up by exact value.
 *
 * <p>Only the JDK crypto provider is used, so no extra dependency is pulled in. GCM gives both
 * confidentiality and integrity: a tampered ciphertext fails to decrypt rather than returning
 * garbage. A fresh random IV per value means the same input never produces the same ciphertext,
 * which is exactly why the separate blind index exists for equality lookups.</p>
 */
@Slf4j
public class CryptoService {

    /** Envelope prefix, so plaintext written before encryption was switched on is still readable. */
    static final String PREFIX = "enc:v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final boolean enabled;
    private final SecretKeySpec dataKey;
    private final SecretKeySpec blindIndexKey;

    public CryptoService(CryptoProperties properties) {
        this.enabled = properties.isEnabled();
        if (this.enabled) {
            // Fail loudly rather than silently deriving a key from an empty string, which would
            // "work" while encrypting every deployment's data under the same well-known key.
            requireKey(properties.getDataKey(), "RETAILBANK_DATA_KEY");
            requireKey(properties.getBlindIndexKey(), "RETAILBANK_BLIND_INDEX_KEY");
        }
        this.dataKey = new SecretKeySpec(normalizeKey(properties.getDataKey()), "AES");
        this.blindIndexKey = new SecretKeySpec(normalizeKey(properties.getBlindIndexKey()), HMAC_ALGORITHM);
    }

    private static void requireKey(String configured, String environmentVariable) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "No PII encryption key is configured. Set the " + environmentVariable
                            + " environment variable, or run with the local 'h2' profile which supplies "
                            + "a throwaway key. Note that changing this key makes existing encrypted "
                            + "data unreadable.");
        }
    }

    /** Returns an envelope encoded ciphertext, or the input unchanged when encryption is disabled. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty() || !enabled) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, dataKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] envelope = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt PII value", e);
        }
    }

    /** Reverses {@link #encrypt}; values without the envelope prefix are returned untouched. */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] envelope = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(envelope, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, dataKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(envelope, IV_LENGTH, envelope.length - IV_LENGTH);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Unable to decrypt PII value", e);
        }
    }

    /**
     * Deterministic, keyed hash of a normalised value. Stored in a side column so that unique
     * constraints and exact-match queries keep working over an encrypted column.
     */
    public String blindIndex(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(blindIndexKey);
            return HexFormat.of().formatHex(mac.doFinal(
                    value.trim().toLowerCase().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to compute blind index", e);
        }
    }

    /** Accepts a Base64 key of any length and folds it to the 32 bytes AES-256 requires. */
    private static byte[] normalizeKey(String configured) {
        if (configured == null || configured.isBlank()) {
            // Only reachable with encryption switched off; the key is never used in that case.
            configured = "disabled";
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException e) {
            raw = configured.getBytes(StandardCharsets.UTF_8);
        }
        if (raw.length == 32) {
            return raw;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

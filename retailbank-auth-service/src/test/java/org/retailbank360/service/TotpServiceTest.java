package org.retailbank360.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the RFC 6238 time-based one-time password implementation. */
class TotpServiceTest {

    private final TotpService totpService = new TotpService();

    @Test
    @DisplayName("A generated secret is Base32 and long enough to be a real key")
    void generatesABase32Secret() {
        String secret = totpService.generateSecret();

        assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
        assertThat(totpService.generateSecret()).isNotEqualTo(secret);
    }

    @Test
    @DisplayName("The current code verifies against its own secret")
    void verifiesTheCurrentCode() {
        String secret = totpService.generateSecret();
        String code = totpService.currentCode(secret);

        assertThat(code).hasSize(6).matches("[0-9]{6}");
        assertThat(totpService.verify(secret, code)).isTrue();
    }

    @Test
    @DisplayName("A code from another secret is rejected")
    void rejectsACodeFromAnotherSecret() {
        String secret = totpService.generateSecret();
        String otherSecret = totpService.generateSecret();

        assertThat(totpService.verify(secret, totpService.currentCode(otherSecret))).isFalse();
    }

    @Test
    @DisplayName("Malformed input is rejected rather than throwing")
    void rejectsMalformedInput() {
        String secret = totpService.generateSecret();

        assertThat(totpService.verify(secret, "12345")).isFalse();
        assertThat(totpService.verify(secret, null)).isFalse();
        assertThat(totpService.verify(null, "123456")).isFalse();
        assertThat(totpService.verify(secret, "abcdef")).isFalse();
    }

    @Test
    @DisplayName("The enrolment URI is in the format authenticator apps expect")
    void buildsAnOtpAuthUri() {
        String uri = totpService.buildOtpAuthUri("retailbank360", "customer1", "JBSWY3DPEHPK3PXP");

        assertThat(uri).startsWith("otpauth://totp/")
                .contains("secret=JBSWY3DPEHPK3PXP")
                .contains("issuer=retailbank360")
                .contains("digits=6")
                .contains("period=30");
    }

    @Test
    @DisplayName("Base32 round-trips, so a secret typed into a phone matches the stored one")
    void base32RoundTrips() {
        byte[] raw = {0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x21, 0x5B, 0x22};

        assertThat(TotpService.base32Decode(TotpService.base32Encode(raw))).isEqualTo(raw);
    }
}

package org.retailbank360.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the masking applied to every value that leaves a service. */
class MaskingUtilTest {

    @Test
    @DisplayName("An account number keeps only its last four digits")
    void masksAccountNumbers() {
        assertThat(MaskingUtil.maskAccountNumber("100200300400")).isEqualTo("XXXXXXXX0400");
        assertThat(MaskingUtil.maskAccountNumber("0400")).isEqualTo("XXXX");
        assertThat(MaskingUtil.maskAccountNumber("40")).isEqualTo("XX");
    }

    @Test
    @DisplayName("A PAN and an Aadhaar keep only their last four characters")
    void masksIdentityNumbers() {
        assertThat(MaskingUtil.maskPan("ABCDE1234F")).isEqualTo("XXXXXX234F");
        assertThat(MaskingUtil.maskAadhaar("987654321012")).isEqualTo("XXXXXXXX1012");
    }

    @Test
    @DisplayName("An email keeps two characters of the local part and the whole domain")
    void masksEmails() {
        assertThat(MaskingUtil.maskEmail("john.doe@example.com")).isEqualTo("jo***@example.com");
        assertThat(MaskingUtil.maskEmail("a@example.com")).isEqualTo("a***@example.com");
        assertThat(MaskingUtil.maskEmail("not-an-email")).isEqualTo("XXXXXXXXXXXX");
    }

    @Test
    @DisplayName("Null and blank inputs pass through untouched")
    void handlesMissingValues() {
        assertThat(MaskingUtil.maskAccountNumber(null)).isNull();
        assertThat(MaskingUtil.maskEmail("")).isEmpty();
        assertThat(MaskingUtil.maskPhone("   ")).isEqualTo("   ");
    }
}

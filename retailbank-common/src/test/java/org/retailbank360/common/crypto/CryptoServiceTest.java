package org.retailbank360.common.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.retailbank360.common.config.CryptoProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for PII encryption at rest and the blind index that keeps it searchable. */
class CryptoServiceTest {

    private static final String DATA_KEY = "dGVzdC1vbmx5LXJldGFpbGJhbmszNjAtYWVzMjU2LWtleSE=";
    private static final String BLIND_INDEX_KEY = "dGVzdC1vbmx5LXJldGFpbGJhbmszNjAtYmxpbmQtaWR4IQ==";

    private CryptoService cryptoService;

    @BeforeEach
    void setUp() {
        cryptoService = new CryptoService(testProperties());
    }

    /**
     * Keys are stated explicitly rather than relying on a default.
     *
     * <p>{@code CryptoProperties} ships with no key at all: a usable one comes from the environment
     * or from the local profile, and the service refuses to start without it.</p>
     */
    private static CryptoProperties testProperties() {
        CryptoProperties properties = new CryptoProperties();
        properties.setDataKey(DATA_KEY);
        properties.setBlindIndexKey(BLIND_INDEX_KEY);
        return properties;
    }

    @Test
    @DisplayName("A value survives an encrypt/decrypt round trip")
    void roundTripsAValue() {
        String plaintext = "ABCDE1234F";
        String ciphertext = cryptoService.encrypt(plaintext);

        assertThat(ciphertext).isNotEqualTo(plaintext).startsWith("enc:v1:");
        assertThat(cryptoService.decrypt(ciphertext)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("The same input encrypts differently every time")
    void producesDistinctCiphertextsForTheSameInput() {
        String first = cryptoService.encrypt("9876543210");
        String second = cryptoService.encrypt("9876543210");

        // A fresh IV per write is what stops an attacker inferring equality from the stored bytes -
        // and exactly why a searchable column needs a separate blind index.
        assertThat(first).isNotEqualTo(second);
        assertThat(cryptoService.decrypt(first)).isEqualTo(cryptoService.decrypt(second));
    }

    @Test
    @DisplayName("A tampered ciphertext is rejected rather than decrypted into garbage")
    void detectsTampering() {
        String ciphertext = cryptoService.encrypt("sensitive-value");
        String tampered = ciphertext.substring(0, ciphertext.length() - 4) + "AAAA";

        assertThatThrownBy(() -> cryptoService.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Plaintext written before encryption was switched on is still readable")
    void passesThroughValuesWithoutTheEnvelope() {
        assertThat(cryptoService.decrypt("legacy-plaintext")).isEqualTo("legacy-plaintext");
        assertThat(cryptoService.decrypt(null)).isNull();
        assertThat(cryptoService.encrypt(null)).isNull();
        assertThat(cryptoService.encrypt("")).isEmpty();
    }

    @Test
    @DisplayName("The blind index is deterministic and case-insensitive, so lookups work")
    void blindIndexIsDeterministic() {
        String index = cryptoService.blindIndex("John.Doe@Example.com");

        assertThat(index).hasSize(64);
        assertThat(cryptoService.blindIndex("john.doe@example.com")).isEqualTo(index);
        assertThat(cryptoService.blindIndex("  JOHN.DOE@EXAMPLE.COM  ")).isEqualTo(index);
        assertThat(cryptoService.blindIndex("jane.doe@example.com")).isNotEqualTo(index);
        assertThat(cryptoService.blindIndex("  ")).isNull();
    }

    @Test
    @DisplayName("A different key produces a different blind index")
    void blindIndexIsKeyed() {
        CryptoProperties otherProperties = testProperties();
        otherProperties.setBlindIndexKey("YSBjb21wbGV0ZWx5LWRpZmZlcmVudC1ibGluZC1pbmRleC1rZXk=");
        CryptoService other = new CryptoService(otherProperties);

        assertThat(other.blindIndex("john.doe@example.com"))
                .as("the index is an HMAC, not a plain hash, so it cannot be built from a rainbow table")
                .isNotEqualTo(cryptoService.blindIndex("john.doe@example.com"));
    }

    @Test
    @DisplayName("A missing key fails loudly instead of silently deriving a well-known one")
    void refusesToStartWithoutAKey() {
        CryptoProperties noKeys = new CryptoProperties();

        assertThatThrownBy(() -> new CryptoService(noKeys))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RETAILBANK_DATA_KEY");

        CryptoProperties noBlindIndexKey = new CryptoProperties();
        noBlindIndexKey.setDataKey(DATA_KEY);
        assertThatThrownBy(() -> new CryptoService(noBlindIndexKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RETAILBANK_BLIND_INDEX_KEY");
    }

    @Test
    @DisplayName("Encryption can be switched off without breaking reads")
    void honoursTheDisabledFlag() {
        CryptoProperties disabled = testProperties();
        disabled.setEnabled(false);
        CryptoService plain = new CryptoService(disabled);

        assertThat(plain.encrypt("value")).isEqualTo("value");
        // Data written while encryption was on stays readable, since the key is unchanged.
        assertThat(plain.decrypt(cryptoService.encrypt("value"))).isEqualTo("value");
    }
}

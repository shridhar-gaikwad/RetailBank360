package org.retailbank360.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code retailbank.crypto.*} - keys used to protect PII at rest. */
@Getter
@Setter
@ConfigurationProperties(prefix = "retailbank.crypto")
public class CryptoProperties {

    private boolean enabled = true;

    /**
     * Base64 encoded 256 bit AES key used by {@code EncryptedStringConverter} for PII columns.
     *
     * <p>Empty by default: a real key comes from {@code RETAILBANK_DATA_KEY} or a secrets manager.
     * The local {@code h2} profile supplies a throwaway one so the POC runs with no setup.</p>
     */
    private String dataKey = "";

    /**
     * Base64 encoded HMAC key used to build blind indexes, so an encrypted column can still be
     * looked up by exact value without ever decrypting the whole table.
     *
     * <p>Empty by default, for the same reason as {@link #dataKey}. Note that rotating this key
     * invalidates every stored index and requires a re-index, so it is not interchangeable with the
     * data key.</p>
     */
    private String blindIndexKey = "";
}

package org.retailbank360.common.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Transparently encrypts a {@code String} column with AES-256-GCM.
 *
 * <p>Apply it to PII fields with {@code @Convert(converter = EncryptedStringConverter.class)}. The
 * ciphertext is what lands in the database; the entity and the rest of the application keep working
 * with the plain value. Because every write uses a fresh IV, columns converted this way must not
 * carry a unique constraint - use a blind index column instead.</p>
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return CryptoHolder.get().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return CryptoHolder.get().decrypt(dbData);
    }
}

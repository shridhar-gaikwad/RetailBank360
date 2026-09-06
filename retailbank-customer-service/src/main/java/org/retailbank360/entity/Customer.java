package org.retailbank360.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.retailbank360.common.crypto.CryptoHolder;
import org.retailbank360.common.crypto.EncryptedStringConverter;
import org.retailbank360.constants.CustomerStatus;
import org.retailbank360.constants.EmploymentType;
import org.retailbank360.constants.KycStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Customer master record.
 *
 * <h2>Protecting PII</h2>
 * Email, phone, address, PAN and Aadhaar are encrypted with AES-256-GCM by
 * {@link EncryptedStringConverter}, so a database dump exposes nothing readable. Because GCM uses a
 * fresh IV per write, the same input never yields the same ciphertext - which would break both
 * unique constraints and exact-match lookups. Each searchable field therefore also carries a keyed
 * <em>blind index</em>: a deterministic HMAC of the normalised value, maintained automatically in
 * the lifecycle callbacks below. Uniqueness and lookups run against the index; the payload stays
 * encrypted.
 *
 * <p>Names are stored in the clear on purpose: they appear on statements and are searched by prefix,
 * and encrypting them would buy little while making the service unusable.</p>
 */
@Entity
@Table(name = "customers",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_customers_number", columnNames = "customer_number"),
                @UniqueConstraint(name = "uk_customers_email_index", columnNames = "email_index"),
                @UniqueConstraint(name = "uk_customers_phone_index", columnNames = "phone_index")
        },
        indexes = {
                @Index(name = "ix_customers_kyc", columnList = "kyc_status"),
                @Index(name = "ix_customers_last_name", columnList = "last_name"),
                @Index(name = "ix_customers_pan_index", columnList = "pan_index")
        })
@Getter
@Setter
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human facing identifier, generated on creation when the caller does not supply one. */
    @Size(max = 30, message = "Customer number must not exceed 30 characters")
    @Column(name = "customer_number", nullable = false, unique = true, length = 30)
    private String customerNumber;

    @NotBlank(message = "First name must not be blank")
    @Size(max = 100, message = "First name must not exceed 100 characters")
    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Size(max = 100, message = "Middle name must not exceed 100 characters")
    @Column(name = "middle_name", length = 100)
    private String middleName;

    @NotBlank(message = "Last name must not be blank")
    @Size(max = 100, message = "Last name must not exceed 100 characters")
    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @NotBlank(message = "Email must not be blank")
    @Email(message = "Email must be valid")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "email", nullable = false, length = 512)
    private String email;

    /** Keyed hash of {@link #email}; carries the uniqueness constraint the ciphertext cannot. */
    @Column(name = "email_index", length = 64)
    private String emailIndex;

    @NotBlank(message = "Phone must not be blank")
    @Pattern(regexp = "^[+]?[0-9]{10,15}$", message = "Phone must be 10 to 15 digits, optionally prefixed with +")
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "phone", nullable = false, length = 512)
    private String phone;

    @Column(name = "phone_index", length = 64)
    private String phoneIndex;

    @NotBlank(message = "Customer address must not be blank")
    @Size(max = 255, message = "Customer address must not exceed 255 characters")
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "customer_address", nullable = false, length = 1024)
    private String customerAddress;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /** Indian permanent account number, e.g. ABCDE1234F. */
    @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]$", message = "PAN must match the format ABCDE1234F")
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "pan_number", length = 512)
    private String panNumber;

    @Column(name = "pan_index", length = 64)
    private String panIndex;

    @Pattern(regexp = "^[0-9]{12}$", message = "Aadhaar must be exactly 12 digits")
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "aadhaar_number", length = 512)
    private String aadhaarNumber;

    @NotNull(message = "KYC status must not be null")
    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 20)
    private KycStatus kycStatus = KycStatus.PENDING;

    @Column(name = "kyc_verified_at")
    private LocalDateTime kycVerifiedAt;

    @Column(name = "kyc_verified_by", length = 120)
    private String kycVerifiedBy;

    @Column(name = "kyc_remarks", length = 500)
    private String kycRemarks;

    /** Declared annual income; an input to loan eligibility. */
    @DecimalMin(value = "0.00", message = "Annual income must not be negative")
    @Column(name = "annual_income", precision = 19, scale = 2)
    private BigDecimal annualIncome;

    /**
     * Credit score placeholder. In production this comes from a bureau; here it is a stored attribute
     * so the loan eligibility rules have something concrete to score against.
     */
    @Min(value = 300, message = "Credit score must be at least 300")
    @Max(value = 900, message = "Credit score must not exceed 900")
    @Column(name = "credit_score")
    private Integer creditScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", length = 30)
    private EmploymentType employmentType;

    @NotNull(message = "Status must not be null")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CustomerStatus status = CustomerStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Optimistic locking: two staff members editing the same customer cannot silently overwrite. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Transient
    public String getFullName() {
        return Stream.of(firstName, middleName, lastName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.joining(" "));
    }

    @Transient
    public boolean isKycVerified() {
        return kycStatus == KycStatus.VERIFIED;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        refreshBlindIndexes();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
        refreshBlindIndexes();
    }

    /** Keeps the searchable hashes in step with the encrypted values they shadow. */
    private void refreshBlindIndexes() {
        emailIndex = CryptoHolder.blindIndex(email);
        phoneIndex = CryptoHolder.blindIndex(phone);
        panIndex = CryptoHolder.blindIndex(panNumber);
    }
}

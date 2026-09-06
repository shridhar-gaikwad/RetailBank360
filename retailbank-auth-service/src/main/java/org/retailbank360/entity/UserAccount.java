package org.retailbank360.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.retailbank360.common.crypto.EncryptedStringConverter;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * A login. Separate from the customer record on purpose: staff logins (teller, loan officer, admin)
 * have no customer at all, and a customer's identity data lives in customer-service.
 */
@Entity
@Table(name = "user_accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_accounts_username", columnNames = "username"),
        indexes = @Index(name = "ix_user_accounts_customer", columnList = "customer_id"))
@Getter
@Setter
@NoArgsConstructor
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Username must not be blank")
    @Size(min = 3, max = 60, message = "Username must be between 3 and 60 characters")
    @Column(name = "username", nullable = false, unique = true, length = 60)
    private String username;

    /** BCrypt hash. The plaintext password is never stored, logged or returned. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /** One of {@code org.retailbank360.common.constants.Roles}. */
    @NotBlank(message = "Role must not be blank")
    @Column(name = "role", nullable = false, length = 30)
    private String role;

    /** Set for {@code CUSTOMER} logins, null for staff. Drives row-level authorization everywhere. */
    @Column(name = "customer_id")
    private Long customerId;

    @Email(message = "Email must be valid")
    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled = false;

    /**
     * TOTP shared secret, encrypted at rest. Anyone holding this value can generate valid codes, so
     * it is treated exactly like a credential.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "mfa_secret", length = 512)
    private String mfaSecret;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    /** Set when the account is temporarily locked out after repeated password failures. */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Optimistic locking, so two admins editing the same login cannot silently overwrite each other. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** True while a lockout window is still running. */
    public boolean isLockedOut() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (passwordChangedAt == null) {
            passwordChangedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

package org.retailbank360.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Server-side record of an issued refresh token, so a session can actually be revoked.
 *
 * <p>Only a SHA-256 hash of the token is stored. A leaked database dump therefore does not hand an
 * attacker usable refresh tokens, exactly as with password hashes.</p>
 */
@Entity
@Table(name = "refresh_tokens",
        uniqueConstraints = @UniqueConstraint(name = "uk_refresh_tokens_hash", columnNames = "token_hash"),
        indexes = {
                @Index(name = "ix_refresh_tokens_user", columnList = "user_id"),
                @Index(name = "ix_refresh_tokens_expires", columnList = "expires_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class RefreshTokenRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked = false;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 120)
    private String revokedReason;

    public boolean isUsable() {
        return !revoked && expiresAt.isAfter(Instant.now());
    }
}

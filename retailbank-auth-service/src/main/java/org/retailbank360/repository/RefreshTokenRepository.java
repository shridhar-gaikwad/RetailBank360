package org.retailbank360.repository;

import org.retailbank360.entity.RefreshTokenRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** Persistence for issued refresh tokens. */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenRecord, Long> {

    Optional<RefreshTokenRecord> findByTokenHash(String tokenHash);

    /** Revokes every live session of one user, e.g. on password change or an admin lockout. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshTokenRecord t
               set t.revoked = true,
                   t.revokedAt = :now,
                   t.revokedReason = :reason
             where t.userId = :userId
               and t.revoked = false
            """)
    int revokeAllForUser(@Param("userId") Long userId,
                         @Param("now") Instant now,
                         @Param("reason") String reason);

    @Modifying(clearAutomatically = true)
    @Query("delete from RefreshTokenRecord t where t.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}

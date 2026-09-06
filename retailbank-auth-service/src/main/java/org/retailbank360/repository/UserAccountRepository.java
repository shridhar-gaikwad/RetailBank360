package org.retailbank360.repository;

import jakarta.persistence.LockModeType;
import org.retailbank360.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Persistence for logins. */
@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);

    List<UserAccount> findByCustomerId(Long customerId);

    /**
     * Row-locked read used when recording a login attempt.
     *
     * <p>Without it, several parallel guesses against the same account each read {@code
     * failedLoginAttempts} before any of them writes, so the counter advances by one instead of by
     * five and the lockout never triggers. The lock serialises those updates.</p>
     *
     * <p>Deliberately keyed on the id and taken inside {@code LoginAttemptService}, not in the login
     * method itself: the login transaction must not hold this row while a nested {@code REQUIRES_NEW}
     * transaction tries to write it, which would be a self-deadlock.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where u.id = :id")
    Optional<UserAccount> findByIdForUpdate(@Param("id") Long id);
}

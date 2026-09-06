package org.retailbank360.service;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.audit.AuditActions;
import org.retailbank360.common.audit.AuditPublisher;
import org.retailbank360.constants.AuthConstants;
import org.retailbank360.entity.UserAccount;
import org.retailbank360.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Records failed login attempts and enforces the lockout.
 *
 * <h2>Why {@code REQUIRES_NEW}</h2>
 * A failed login always ends by throwing, and that exception rolls the caller's transaction back. If
 * the counter were incremented in that same transaction it would be discarded along with everything
 * else, so the threshold would never be reached and the lockout would never fire - a brute-force
 * guard that silently does nothing. Committing the attempt in its own transaction is what makes it
 * survive the rollback.
 *
 * <p>The read is row-locked by the caller, so parallel guesses against one account serialise and
 * each attempt is counted exactly once.</p>
 */
@Slf4j
@Service
public class LoginAttemptService {

    private final UserAccountRepository userRepository;
    private final AuditPublisher auditPublisher;

    public LoginAttemptService(UserAccountRepository userRepository, AuditPublisher auditPublisher) {
        this.userRepository = userRepository;
        this.auditPublisher = auditPublisher;
    }

    /** Counts one bad attempt, and locks the account once the threshold is crossed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerFailure(Long userId) {
        UserAccount user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null) {
            return;
        }

        int attempts = user.getFailedLoginAttempts() + 1;
        if (attempts >= AuthConstants.MAX_FAILED_ATTEMPTS) {
            user.setLockedUntil(Instant.now().plus(AuthConstants.LOCKOUT_DURATION));
            user.setFailedLoginAttempts(0);
            log.warn("Locked account '{}' for {} after {} failed attempts",
                    user.getUsername(), AuthConstants.LOCKOUT_DURATION, attempts);
            auditPublisher.publishFailure(AuditActions.LOGIN_LOCKED_OUT, "USER", userId, null,
                    "Locked after " + attempts + " failed attempts");
        } else {
            user.setFailedLoginAttempts(attempts);
            auditPublisher.publishFailure(AuditActions.LOGIN_FAILED, "USER", userId, null,
                    "Bad credentials, attempt " + attempts);
        }
        userRepository.save(user);
    }

    /** Clears the counter after a successful login. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerSuccess(Long userId) {
        userRepository.findByIdForUpdate(userId).ifPresent(user -> {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            user.setLastLoginAt(java.time.LocalDateTime.now());
            userRepository.save(user);
        });
    }
}

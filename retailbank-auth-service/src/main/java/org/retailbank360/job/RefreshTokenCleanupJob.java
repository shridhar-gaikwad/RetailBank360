package org.retailbank360.job;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.repository.RefreshTokenRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Trims refresh tokens whose lifetime has passed.
 *
 * <p>Without this the table only ever grows: every login adds a row, and rotation on refresh adds
 * another. A token past its expiry is already refused by {@code AuthServiceImpl}, so the row carries
 * no meaning - it is just a hashed credential sitting in the database for no reason.</p>
 *
 * <p>Revoked-but-unexpired rows are kept deliberately: they still answer "was this session ended?"
 * until the token would have expired anyway.</p>
 */
@Slf4j
@Component
public class RefreshTokenCleanupJob {

    /** Hourly is ample; tokens live for days. */
    private static final long ONE_HOUR_MILLIS = 3_600_000L;

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenCleanupJob(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Scheduled(fixedDelay = ONE_HOUR_MILLIS, initialDelay = ONE_HOUR_MILLIS)
    @Transactional
    public void purgeExpiredTokens() {
        try {
            int deleted = refreshTokenRepository.deleteExpired(Instant.now());
            if (deleted > 0) {
                log.info("Purged {} expired refresh token(s)", deleted);
            }
        } catch (RuntimeException e) {
            log.error("Refresh token purge failed; will retry on the next tick", e);
        }
    }
}

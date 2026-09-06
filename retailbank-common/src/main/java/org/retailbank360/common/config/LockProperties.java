package org.retailbank360.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Binds {@code retailbank.lock.*} - tuning for the distributed resource lock. */
@Getter
@Setter
@ConfigurationProperties(prefix = "retailbank.lock")
public class LockProperties {

    private boolean enabled = true;

    /**
     * Identifies this JVM inside the lock table. Leave empty to derive it from the application name,
     * the host name and the PID, which keeps two instances of the same service distinguishable.
     */
    private String nodeId = "";

    /**
     * How long a lock stays valid without being renewed. This is the orphan-recovery window: if the
     * owning process crashes, the lock becomes stealable once the TTL elapses.
     */
    private Duration defaultTtl = Duration.ofSeconds(30);

    /** How long a caller waits for a busy lock before giving up with HTTP 423. */
    private Duration defaultWait = Duration.ofSeconds(5);

    /** First back-off between acquisition attempts; doubles up to {@link #maxRetryDelay}. */
    private Duration retryDelay = Duration.ofMillis(50);

    private Duration maxRetryDelay = Duration.ofMillis(500);

    /** How often expired locks left behind by crashed processes are swept away. */
    private Duration reaperInterval = Duration.ofSeconds(30);

    /** Expired rows are only deleted once they are this much older than their expiry. */
    private Duration reaperGracePeriod = Duration.ofSeconds(10);

    /** Retries applied when the database itself reports a deadlock or a lock wait timeout. */
    private int deadlockRetries = 3;

    private Duration deadlockRetryDelay = Duration.ofMillis(100);

    /** Keep lock audit rows for this long; the reaper trims anything older. */
    private Duration auditRetention = Duration.ofDays(30);
}

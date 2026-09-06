package org.retailbank360.constants;

import java.time.Duration;

/** Login hardening thresholds. */
public final class AuthConstants {

    /** Consecutive password failures tolerated before the account is temporarily locked. */
    public static final int MAX_FAILED_ATTEMPTS = 5;

    /** How long an account stays locked after too many failures. */
    public static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    /** TOTP step, digits and drift tolerance (RFC 6238 defaults). */
    public static final int TOTP_TIME_STEP_SECONDS = 30;
    public static final int TOTP_DIGITS = 6;
    public static final int TOTP_ALLOWED_DRIFT_STEPS = 1;

    private AuthConstants() {
    }
}

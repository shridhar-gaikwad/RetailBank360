package org.retailbank360.dto;

import lombok.Builder;
import lombok.Data;

/**
 * One-time output of enabling TOTP.
 *
 * <p>The shared secret is shown exactly once, at enrolment. After that it is only ever readable in
 * encrypted form in the database.</p>
 */
@Data
@Builder
public class MfaSetupResponse {

    private String username;

    /** Base32 secret to type into an authenticator app. */
    private String secret;

    /** {@code otpauth://} URI that an authenticator app can consume as a QR code. */
    private String otpAuthUri;

    private String message;
}

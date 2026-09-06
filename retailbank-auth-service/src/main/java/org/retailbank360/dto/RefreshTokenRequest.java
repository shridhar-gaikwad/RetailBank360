package org.retailbank360.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Exchanges a refresh token for a fresh access token. */
@Data
public class RefreshTokenRequest {

    @NotBlank(message = "Refresh token must not be blank")
    private String refreshToken;
}

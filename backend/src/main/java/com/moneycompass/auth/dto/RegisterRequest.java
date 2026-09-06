package com.moneycompass.auth.dto;

import com.moneycompass.domain.ProfileType;
import jakarta.validation.constraints.*;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,

        // 12 is the floor rather than 8: this is the only credential in the
        // system and there is no MFA behind it.
        @NotBlank @Size(min = 12, max = 72, message = "password must be 12 to 72 characters")
        String password,

        @NotNull ProfileType profileType
) {}

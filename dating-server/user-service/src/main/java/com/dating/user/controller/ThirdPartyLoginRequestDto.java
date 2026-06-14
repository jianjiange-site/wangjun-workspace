package com.dating.user.controller;

import jakarta.validation.constraints.NotBlank;

public record ThirdPartyLoginRequestDto(
        @NotBlank String provider,
        @NotBlank String providerUserId,
        @NotBlank String requestId
) {
}

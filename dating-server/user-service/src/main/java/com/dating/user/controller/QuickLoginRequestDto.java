package com.dating.user.controller;

import jakarta.validation.constraints.NotBlank;

public record QuickLoginRequestDto(
        @NotBlank String deviceId,
        @NotBlank String installId,
        @NotBlank String quickLoginToken
) {
}

package com.dating.user.controller;

import jakarta.validation.constraints.NotBlank;

public record PhoneLoginRequestDto(
        @NotBlank String phoneNumber,
        @NotBlank String verificationCode,
        @NotBlank String requestId
) {
}

package com.dating.user.controller;

import jakarta.validation.constraints.NotBlank;

public record PhoneVerificationCodeRequestDto(
        @NotBlank String phoneNumber
) {
}

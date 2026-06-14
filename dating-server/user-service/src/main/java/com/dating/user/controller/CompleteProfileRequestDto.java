package com.dating.user.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CompleteProfileRequestDto(
        @NotBlank String userId,
        @NotBlank String nickname,
        @NotBlank String gender,
        @NotNull LocalDate birthDate,
        @NotBlank String avatarMediaId,
        @NotBlank String race
) {
}

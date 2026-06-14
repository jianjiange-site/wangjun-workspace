package com.dating.user.controller;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record UpdateBasicInfoRequestDto(
        @NotBlank String userId,
        List<String> tags,
        String city,
        String bio,
        String occupation,
        Integer heightCm,
        Integer weightKg,
        String education,
        String mbti
) {
}

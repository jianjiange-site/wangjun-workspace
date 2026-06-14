package com.dating.user.controller;

import java.time.LocalDate;
import java.util.List;

public record UserProfileVo(
        String userId,
        String nickname,
        String gender,
        LocalDate birthDate,
        String avatarMediaId,
        String race,
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

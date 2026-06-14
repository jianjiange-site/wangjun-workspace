package com.dating.user.controller;

public record LoginResponseVo(
        String userId,
        String status,
        String nextAction,
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        boolean newUser
) {
}

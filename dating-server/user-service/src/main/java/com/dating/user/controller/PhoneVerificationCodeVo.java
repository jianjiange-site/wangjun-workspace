package com.dating.user.controller;

public record PhoneVerificationCodeVo(
        String phoneNumber,
        String verificationCode,
        long expiresInSeconds
) {
}

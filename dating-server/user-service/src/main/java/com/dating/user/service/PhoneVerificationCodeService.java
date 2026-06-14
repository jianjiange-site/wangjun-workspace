package com.dating.user.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;

@Service
public class PhoneVerificationCodeService {

    private static final String CODE_KEY_PREFIX = "user:phone-login-code:";
    private static final String ATTEMPT_KEY_PREFIX = "user:phone-login-code-attempt:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom;
    private final Duration ttl;
    private final int maxAttempts;

    @Autowired
    public PhoneVerificationCodeService(StringRedisTemplate redisTemplate) {
        this(redisTemplate, new SecureRandom(), DEFAULT_TTL, DEFAULT_MAX_ATTEMPTS);
    }

    PhoneVerificationCodeService(
            StringRedisTemplate redisTemplate,
            SecureRandom secureRandom,
            Duration ttl,
            int maxAttempts
    ) {
        this.redisTemplate = redisTemplate;
        this.secureRandom = secureRandom;
        this.ttl = ttl;
        this.maxAttempts = maxAttempts;
    }

    public String createCode(String phoneNumber) {
        String code = "%06d".formatted(secureRandom.nextInt(1_000_000));
        redisTemplate.opsForValue().set(codeKey(phoneNumber), code, ttl);
        redisTemplate.delete(attemptKey(phoneNumber));
        return code;
    }

    public PhoneVerificationCodeVerifyResult consumeCode(String phoneNumber, String verificationCode) {
        String storedCode = redisTemplate.opsForValue().get(codeKey(phoneNumber));
        if (storedCode == null) {
            return PhoneVerificationCodeVerifyResult.INVALID;
        }

        if (storedCode.equals(verificationCode)) {
            clearCodeAndAttempts(phoneNumber);
            return PhoneVerificationCodeVerifyResult.MATCHED;
        }

        long attempts = incrementAttempts(phoneNumber);
        if (attempts >= maxAttempts) {
            clearCodeAndAttempts(phoneNumber);
            return PhoneVerificationCodeVerifyResult.RETRY_LIMIT_EXCEEDED;
        }

        return PhoneVerificationCodeVerifyResult.INVALID;
    }

    public long expiresInSeconds() {
        return ttl.toSeconds();
    }

    private long incrementAttempts(String phoneNumber) {
        String key = attemptKey(phoneNumber);
        Long attempts = redisTemplate.opsForValue().increment(key);
        if (attempts != null && attempts == 1L) {
            redisTemplate.expire(key, ttl);
        }
        return attempts == null ? 0L : attempts;
    }

    private void clearCodeAndAttempts(String phoneNumber) {
        redisTemplate.delete(List.of(codeKey(phoneNumber), attemptKey(phoneNumber)));
    }

    private static String codeKey(String phoneNumber) {
        return CODE_KEY_PREFIX + phoneNumber;
    }

    private static String attemptKey(String phoneNumber) {
        return ATTEMPT_KEY_PREFIX + phoneNumber;
    }
}

package com.dating.user.manager;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhoneVerificationCodeManagerTests {

    @Test
    void generatedCodeIsStoredInRedisForFiveMinutesAndClearsPreviousRetries() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mockValueOperations(redisTemplate);
        PhoneVerificationCodeManager manager = new PhoneVerificationCodeManager(
                redisTemplate,
                fixedRandom(123456),
                Duration.ofMinutes(5),
                5
        );

        String code = manager.createCode("13800138000");

        assertThat(code).isEqualTo("123456");
        assertThat(manager.expiresInSeconds()).isEqualTo(300);
        verify(valueOperations).set("user:phone-login-code:13800138000", "123456", Duration.ofMinutes(5));
        verify(redisTemplate).delete("user:phone-login-code-attempt:13800138000");
    }

    @Test
    void matchingCodeDeletesRedisCodeAndAttemptKeys() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mockValueOperations(redisTemplate);
        when(valueOperations.get("user:phone-login-code:13800138000")).thenReturn("654321");
        PhoneVerificationCodeManager manager = new PhoneVerificationCodeManager(
                redisTemplate,
                fixedRandom(654321),
                Duration.ofMinutes(5),
                5
        );

        assertThat(manager.consumeCode("13800138000", "654321"))
                .isEqualTo(PhoneVerificationCodeVerifyResult.MATCHED);
        verify(redisTemplate).delete(List.of(
                "user:phone-login-code:13800138000",
                "user:phone-login-code-attempt:13800138000"
        ));
    }

    private static SecureRandom fixedRandom(int value) {
        return new SecureRandom() {
            @Override
            public int nextInt(int bound) {
                return value;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> mockValueOperations(StringRedisTemplate redisTemplate) {
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        return valueOperations;
    }
}

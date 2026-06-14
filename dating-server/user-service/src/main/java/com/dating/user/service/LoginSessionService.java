package com.dating.user.service;

import com.dating.user.enums.LoginSessionStatusEnum;
import com.dating.user.enums.LoginTypeEnum;
import com.dating.user.model.UserSessionEntity;
import com.dating.user.repository.UserSessionRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class LoginSessionService {

    private static final String REDIS_KEY_PREFIX = "login_session:";
    private static final Duration SESSION_TOKEN_TTL = Duration.ofMinutes(10);

    private final UserSessionRepository sessionRepository;
    private final StringRedisTemplate redisTemplate;

    public LoginSessionService(UserSessionRepository sessionRepository, StringRedisTemplate redisTemplate) {
        this.sessionRepository = sessionRepository;
        this.redisTemplate = redisTemplate;
    }

    public String createSession(String userId, LoginTypeEnum loginType, String deviceId) {
        String sessionId = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();

        UserSessionEntity session = new UserSessionEntity();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setLoginType(loginType.name());
        session.setDeviceId(deviceId);
        session.setStatus(LoginSessionStatusEnum.CREDENTIAL_VERIFIED.name());
        session.setLivenessRequired(true);
        session.setLivenessStatus("PENDING");
        session.setExpireAt(Instant.now().plus(SESSION_TOKEN_TTL));
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        sessionRepository.insert(session);

        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + token, sessionId, SESSION_TOKEN_TTL);
        return token;
    }

    public String resolveSessionToken(String token) {
        return redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + token);
    }

    public boolean isSessionTokenValid(String token) {
        return resolveSessionToken(token) != null;
    }

    public void invalidateSession(String token) {
        redisTemplate.delete(REDIS_KEY_PREFIX + token);
    }
}

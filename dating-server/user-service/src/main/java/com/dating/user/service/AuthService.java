package com.dating.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.user.client.SmsVerificationClient;
import com.dating.user.common.SnowflakeIdGenerator;
import com.dating.user.controller.request.*;
import com.dating.user.controller.response.LoginSessionResponse;
import com.dating.user.enums.LoginTypeEnum;
import com.dating.user.enums.NextStepEnum;
import com.dating.user.exception.ErrorCode;
import com.dating.user.exception.UserServiceException;
import com.dating.user.model.*;
import com.dating.user.repository.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final UserAuthIdentityRepository identityRepository;
    private final UserDeviceRepository deviceRepository;
    private final UserRefreshTokenRepository refreshTokenRepository;
    private final PhoneVerificationCodeService codeService;
    private final SmsVerificationClient smsClient;
    private final TokenService tokenService;
    private final LoginSessionService sessionService;
    private final SnowflakeIdGenerator idGenerator;

    public AuthService(
            UserRepository userRepository,
            UserAuthIdentityRepository identityRepository,
            UserDeviceRepository deviceRepository,
            UserRefreshTokenRepository refreshTokenRepository,
            PhoneVerificationCodeService codeService,
            SmsVerificationClient smsClient,
            TokenService tokenService,
            LoginSessionService sessionService,
            SnowflakeIdGenerator idGenerator) {
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
        this.deviceRepository = deviceRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.codeService = codeService;
        this.smsClient = smsClient;
        this.tokenService = tokenService;
        this.sessionService = sessionService;
        this.idGenerator = idGenerator;
    }

    // --- SMS ---
    public void sendLoginCode(SendSmsCodeRequest request) {
        String phone = normalizePhone(request.getPhoneCountryCode(), request.getPhone());
        String code = codeService.createCode(phone);
        smsClient.sendLoginCode(phone, code);
    }

    // --- Phone login ---
    @Transactional
    public LoginSessionResponse phoneLogin(PhoneLoginRequest request) {
        String phone = normalizePhone(request.getPhoneCountryCode(), request.getPhone());
        verifyCode(phone, request.getSmsCode());

        String userId = findOrCreateUserByPhone(phone);
        upsertDevice(userId, request.getDeviceId(), request.getDeviceType());
        String sessionToken = sessionService.createSession(userId, LoginTypeEnum.PHONE, request.getDeviceId());

        return new LoginSessionResponse(sessionToken, NextStepEnum.LIVENESS_REQUIRED.name());
    }

    // --- Google login ---
    @Transactional
    public LoginSessionResponse googleLogin(GoogleLoginRequest request) {
        String userId = findOrCreateUserByIdentity(request.getProvider(), request.getProviderUserId());
        upsertDevice(userId, request.getDeviceId(), request.getDeviceType());
        String sessionToken = sessionService.createSession(userId, LoginTypeEnum.GOOGLE, request.getDeviceId());

        return new LoginSessionResponse(sessionToken, NextStepEnum.LIVENESS_REQUIRED.name());
    }

    // --- Quick login ---
    @Transactional
    public Object quickLogin(QuickLoginRequest request) {
        String tokenHash = tokenService.hashToken(request.getRefreshToken());
        LambdaQueryWrapper<UserRefreshTokenEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRefreshTokenEntity::getRefreshTokenHash, tokenHash)
                .eq(UserRefreshTokenEntity::getRevoked, false);
        UserRefreshTokenEntity stored = refreshTokenRepository.selectOne(wrapper);

        if (stored == null || stored.getExpireAt().isBefore(Instant.now())) {
            throw new UserServiceException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        stored.setRevoked(true);
        stored.setUpdatedAt(Instant.now());
        refreshTokenRepository.updateById(stored);

        boolean highRisk = !request.getDeviceId().equals(stored.getDeviceId());
        if (highRisk) {
            String sessionToken = sessionService.createSession(stored.getUserId(), LoginTypeEnum.QUICK, request.getDeviceId());
            return new LoginSessionResponse(sessionToken, NextStepEnum.LIVENESS_REQUIRED.name());
        }

        UserEntity user = findUserById(stored.getUserId());
        String accessToken = tokenService.generateAccessToken(stored.getUserId(), user.getUserType());
        String newRefresh = tokenService.generateRefreshToken();
        String newHash = tokenService.hashToken(newRefresh);

        UserRefreshTokenEntity newToken = new UserRefreshTokenEntity();
        newToken.setUserId(stored.getUserId());
        newToken.setDeviceId(request.getDeviceId());
        newToken.setRefreshTokenHash(newHash);
        newToken.setExpireAt(Instant.now().plusSeconds(tokenService.getRefreshTokenExpiresInSeconds()));
        newToken.setRevoked(false);
        newToken.setCreatedAt(Instant.now());
        newToken.setUpdatedAt(Instant.now());
        refreshTokenRepository.insert(newToken);

        boolean completed = user.getProfileCompleted() != null && user.getProfileCompleted();
        return new QuickLoginTokenResponse(accessToken, newRefresh,
                tokenService.getAccessTokenExpiresInSeconds(), stored.getUserId(), user.getUserType(),
                completed, getAvatarAuditStatus(user), NextStepEnum.TOKEN_ISSUED.name());
    }

    // --- Logout ---
    @Transactional
    public void logout(String userId) {
        LambdaQueryWrapper<UserRefreshTokenEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRefreshTokenEntity::getUserId, userId)
                .eq(UserRefreshTokenEntity::getRevoked, false);
        UserRefreshTokenEntity token = refreshTokenRepository.selectOne(wrapper);
        if (token != null) {
            token.setRevoked(true);
            token.setUpdatedAt(Instant.now());
            refreshTokenRepository.updateById(token);
        }
    }

    // --- Issue tokens ---
    @Transactional
    public Object issueTokens(String userId, String deviceId) {
        UserEntity user = findUserById(userId);
        if ("FROZEN".equals(user.getStatus())) {
            throw new UserServiceException(ErrorCode.USER_FROZEN);
        }

        LambdaQueryWrapper<UserRefreshTokenEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRefreshTokenEntity::getUserId, userId)
                .eq(UserRefreshTokenEntity::getRevoked, false);
        UserRefreshTokenEntity oldToken = refreshTokenRepository.selectOne(wrapper);
        if (oldToken != null) {
            oldToken.setRevoked(true);
            oldToken.setUpdatedAt(Instant.now());
            refreshTokenRepository.updateById(oldToken);
        }

        String accessToken = tokenService.generateAccessToken(userId, user.getUserType());
        String refresh = tokenService.generateRefreshToken();
        String hash = tokenService.hashToken(refresh);

        UserRefreshTokenEntity newToken = new UserRefreshTokenEntity();
        newToken.setUserId(userId);
        newToken.setDeviceId(deviceId);
        newToken.setRefreshTokenHash(hash);
        newToken.setExpireAt(Instant.now().plusSeconds(tokenService.getRefreshTokenExpiresInSeconds()));
        newToken.setRevoked(false);
        newToken.setCreatedAt(Instant.now());
        newToken.setUpdatedAt(Instant.now());
        refreshTokenRepository.insert(newToken);

        boolean completed = user.getProfileCompleted() != null && user.getProfileCompleted();
        return new QuickLoginTokenResponse(accessToken, refresh,
                tokenService.getAccessTokenExpiresInSeconds(), userId, user.getUserType(),
                completed, getAvatarAuditStatus(user), NextStepEnum.TOKEN_ISSUED.name());
    }

    // --- Helpers ---
    private String findOrCreateUserByPhone(String phone) {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserEntity::getPhone, phone).eq(UserEntity::getDeleted, false);
        UserEntity user = userRepository.selectOne(wrapper);
        if (user != null) {
            user.setLastLoginAt(Instant.now());
            user.setUpdatedAt(Instant.now());
            userRepository.updateById(user);
            return user.getUserId();
        }
        return createUser(phone);
    }

    private String findOrCreateUserByIdentity(String provider, String providerUserId) {
        LambdaQueryWrapper<UserAuthIdentityEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserAuthIdentityEntity::getProvider, provider)
                .eq(UserAuthIdentityEntity::getIdentityId, providerUserId)
                .eq(UserAuthIdentityEntity::getDeleted, false);
        UserAuthIdentityEntity identity = identityRepository.selectOne(wrapper);
        if (identity != null) {
            updateUserLastLogin(identity.getUserId());
            return identity.getUserId();
        }
        return createUser(null, provider, providerUserId);
    }

    private String createUser(String phone) {
        String userId = idGenerator.nextId();
        UserEntity user = new UserEntity();
        user.setUserId(userId);
        user.setUserType("BH");
        user.setStatus("ACTIVE");
        if (phone != null) user.setPhone(phone);
        user.setProfileCompleted(false);
        user.setLivenessStatus("PENDING");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.insert(user);
        return userId;
    }

    private String createUser(String phone, String provider, String providerUserId) {
        String userId = idGenerator.nextId();
        UserEntity user = new UserEntity();
        user.setUserId(userId);
        user.setUserType("BH");
        user.setStatus("ACTIVE");
        if (phone != null) user.setPhone(phone);
        user.setProfileCompleted(false);
        user.setLivenessStatus("PENDING");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.insert(user);

        UserAuthIdentityEntity identity = new UserAuthIdentityEntity();
        identity.setUserId(userId);
        identity.setIdentityType(provider);
        identity.setProvider(provider);
        identity.setIdentityId(providerUserId);
        identity.setCreatedAt(Instant.now());
        identity.setUpdatedAt(Instant.now());
        identityRepository.insert(identity);

        return userId;
    }

    private void updateUserLastLogin(String userId) {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserEntity::getUserId, userId).eq(UserEntity::getDeleted, false);
        UserEntity user = userRepository.selectOne(wrapper);
        if (user != null) {
            user.setLastLoginAt(Instant.now());
            user.setUpdatedAt(Instant.now());
            userRepository.updateById(user);
        }
    }

    private UserEntity findUserById(String userId) {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserEntity::getUserId, userId).eq(UserEntity::getDeleted, false);
        UserEntity user = userRepository.selectOne(wrapper);
        if (user == null) throw new UserServiceException(ErrorCode.USER_NOT_FOUND);
        return user;
    }

    private void upsertDevice(String userId, String deviceId, String deviceType) {
        LambdaQueryWrapper<UserDeviceEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserDeviceEntity::getUserId, userId)
                .eq(UserDeviceEntity::getDeviceId, deviceId)
                .eq(UserDeviceEntity::getDeleted, false);
        UserDeviceEntity device = deviceRepository.selectOne(wrapper);
        if (device != null) {
            device.setLastLoginAt(Instant.now());
            device.setUpdatedAt(Instant.now());
            deviceRepository.updateById(device);
        } else {
            device = new UserDeviceEntity();
            device.setUserId(userId);
            device.setDeviceId(deviceId);
            device.setDeviceType(deviceType);
            device.setTrusted(false);
            device.setLastLoginAt(Instant.now());
            device.setCreatedAt(Instant.now());
            device.setUpdatedAt(Instant.now());
            deviceRepository.insert(device);
        }
    }

    private void verifyCode(String phone, String code) {
        PhoneVerificationCodeVerifyResult result = codeService.consumeCode(phone, code);
        if (result == PhoneVerificationCodeVerifyResult.RETRY_LIMIT_EXCEEDED) {
            throw new UserServiceException(ErrorCode.SMS_CODE_INVALID, "验证码重试次数超过限制");
        }
        if (result != PhoneVerificationCodeVerifyResult.MATCHED) {
            throw new UserServiceException(ErrorCode.SMS_CODE_INVALID);
        }
    }

    private String normalizePhone(String countryCode, String phone) {
        if (phone == null || phone.isBlank()) {
            throw new UserServiceException(ErrorCode.BAD_REQUEST, "手机号不能为空");
        }
        return (countryCode != null ? countryCode : "+86") + ":" + phone.trim();
    }

    private String getAvatarAuditStatus(UserEntity user) {
        return user.getProfileCompleted() != null && user.getProfileCompleted() ? "PASSED" : "NONE";
    }

    @Data
    @AllArgsConstructor
    public static class QuickLoginTokenResponse {
        private String accessToken;
        private String refreshToken;
        private long expiresIn;
        private String userId;
        private String userType;
        private boolean profileCompleted;
        private String avatarAuditStatus;
        private String nextStep;
    }
}

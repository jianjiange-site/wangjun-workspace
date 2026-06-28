package site.jianjiange.mobilegateway.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import site.jianjiange.mobilegateway.entity.GatewayRefreshTokenEntity;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;
import site.jianjiange.mobilegateway.mapper.GatewayRefreshTokenMapper;
import site.jianjiange.mobilegateway.service.JwtService;
import site.jianjiange.mobilegateway.support.HmacHasher;
import site.jianjiange.mobilegateway.support.RedisKeyFactory;
import site.jianjiange.mobilegateway.support.SnowflakeIdGenerator;

/**
 * refresh token 会话管理器，负责首次登录 token 持久化和 Redis 缓存写入。
 */
@Component
@Slf4j
public class RefreshTokenManager {

    private static final OffsetDateTime EPOCH = OffsetDateTime.parse("1970-01-01T00:00:00Z");

    private final GatewayRefreshTokenMapper refreshTokenMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final HmacHasher hmacHasher;
    private final JwtService jwtService;
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory redisKeyFactory;

    /**
     * 创建 refresh token 管理器。
     *
     * @param refreshTokenMapper refresh token 表 Mapper
     * @param idGenerator 雪花 ID 生成器
     * @param hmacHasher HMAC hash 工具
     * @param jwtService token 签发和解析服务
     * @param redisTemplate Redis 字符串客户端
     * @param redisKeyFactory Redis key 工厂
     */
    public RefreshTokenManager(GatewayRefreshTokenMapper refreshTokenMapper, SnowflakeIdGenerator idGenerator,
                               HmacHasher hmacHasher, JwtService jwtService, StringRedisTemplate redisTemplate,
                               RedisKeyFactory redisKeyFactory) {
        this.refreshTokenMapper = refreshTokenMapper;
        this.idGenerator = idGenerator;
        this.hmacHasher = hmacHasher;
        this.jwtService = jwtService;
        this.redisTemplate = redisTemplate;
        this.redisKeyFactory = redisKeyFactory;
    }

    /**
     * 持久化首次登录签发的 refresh token，并写入 Redis 短缓存。
     *
     * @param identity 登录身份信息
     * @param tokens 本次签发的 token 信息
     */
    @Transactional
    public void persistInitial(AuthServiceLoginIdentity identity, JwtService.IssuedTokens tokens) {
        OffsetDateTime now = tokens.issuedAt();
        String tokenHash = hmacHasher.hashRefreshToken(tokens.refreshTokenSecret());
        GatewayRefreshTokenEntity refreshToken = new GatewayRefreshTokenEntity();
        refreshToken.setRefreshTokenId(idGenerator.nextId());
        refreshToken.setAccountId(identity.accountId());
        refreshToken.setDeviceId(identity.deviceId());
        refreshToken.setUserId(identity.userId());
        refreshToken.setTokenJti(tokens.refreshTokenJti());
        refreshToken.setTokenHash(tokenHash);
        refreshToken.setStatus(AuthConstants.REFRESH_STATUS_ACTIVE);
        refreshToken.setIssuedAt(now);
        refreshToken.setExpiresAt(tokens.refreshTokenExpiresAt());
        refreshToken.setRevokedAt(EPOCH);
        refreshToken.setCreatedAt(now);
        refreshToken.setUpdatedAt(now);
        refreshToken.setDeleted(AuthConstants.DELETED_NO);
        refreshTokenMapper.insert(refreshToken);
        cacheRefreshTokenAfterCommit(tokens.refreshTokenJti(), tokenHash, now, tokens.refreshTokenExpiresAt());
    }

    /**
     * 校验旧 refresh token，条件轮换旧会话，并持久化新 refresh token。
     *
     * @param refreshTokenValue refresh token 明文
     * @return 新签发的 token 信息
     */
    @Transactional
    public JwtService.IssuedTokens rotate(String refreshTokenValue) {
        JwtService.ParsedRefreshToken parsedToken = jwtService.parseRefreshToken(refreshTokenValue);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        GatewayRefreshTokenEntity existingToken = refreshTokenMapper.selectOne(
                new LambdaQueryWrapper<GatewayRefreshTokenEntity>()
                        .eq(GatewayRefreshTokenEntity::getTokenJti, parsedToken.jti())
                        .eq(GatewayRefreshTokenEntity::getDeleted, AuthConstants.DELETED_NO)
                        .last("limit 1"));
        validateRefreshToken(existingToken, parsedToken, now);

        JwtService.IssuedTokens newTokens = jwtService.issue(existingToken.getAccountId(), existingToken.getUserId(),
                existingToken.getDeviceId());
        int updated = rotateActiveToken(parsedToken.jti(), now);
        if (updated != 1) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_REUSED);
        }
        persistRotated(existingToken, newTokens);
        return newTokens;
    }

    /**
     * 校验 refresh token 数据库状态、有效期和 HMAC hash。
     *
     * @param existingToken 数据库中的 refresh token 会话
     * @param parsedToken refresh token 明文解析结果
     * @param now 当前时间
     */
    private void validateRefreshToken(GatewayRefreshTokenEntity existingToken, JwtService.ParsedRefreshToken parsedToken,
                                      OffsetDateTime now) {
        if (existingToken == null) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }
        if (existingToken.getStatus() == AuthConstants.REFRESH_STATUS_EXPIRED
                || existingToken.getExpiresAt() == null
                || !now.isBefore(existingToken.getExpiresAt())) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_EXPIRED);
        }
        if (existingToken.getStatus() == AuthConstants.REFRESH_STATUS_REVOKED) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_REVOKED);
        }
        if (existingToken.getStatus() == AuthConstants.REFRESH_STATUS_ROTATED) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_REUSED);
        }
        if (existingToken.getStatus() != AuthConstants.REFRESH_STATUS_ACTIVE) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }
        String expectedHash = existingToken.getTokenHash();
        String actualHash = hmacHasher.hashRefreshToken(parsedToken.secret());
        if (!StringUtils.hasText(expectedHash)) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }
        if (!MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.UTF_8),
                actualHash.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }
    }

    /**
     * 通过条件更新抢占旧 refresh token 的轮换权。
     *
     * @param tokenJti 旧 refresh token jti
     * @param now 当前时间
     * @return 更新行数
     */
    private int rotateActiveToken(String tokenJti, OffsetDateTime now) {
        GatewayRefreshTokenEntity update = new GatewayRefreshTokenEntity();
        update.setStatus(AuthConstants.REFRESH_STATUS_ROTATED);
        update.setUpdatedAt(now);
        return refreshTokenMapper.update(update, new LambdaUpdateWrapper<GatewayRefreshTokenEntity>()
                .eq(GatewayRefreshTokenEntity::getTokenJti, tokenJti)
                .eq(GatewayRefreshTokenEntity::getStatus, AuthConstants.REFRESH_STATUS_ACTIVE)
                .eq(GatewayRefreshTokenEntity::getDeleted, AuthConstants.DELETED_NO));
    }

    /**
     * 插入轮换后的新 refresh token 会话。
     *
     * @param oldToken 旧 refresh token 会话
     * @param newTokens 新签发 token 信息
     */
    private void persistRotated(GatewayRefreshTokenEntity oldToken, JwtService.IssuedTokens newTokens) {
        String tokenHash = hmacHasher.hashRefreshToken(newTokens.refreshTokenSecret());
        GatewayRefreshTokenEntity refreshToken = new GatewayRefreshTokenEntity();
        refreshToken.setRefreshTokenId(idGenerator.nextId());
        refreshToken.setAccountId(oldToken.getAccountId());
        refreshToken.setDeviceId(oldToken.getDeviceId());
        refreshToken.setUserId(oldToken.getUserId());
        refreshToken.setTokenJti(newTokens.refreshTokenJti());
        refreshToken.setTokenHash(tokenHash);
        refreshToken.setStatus(AuthConstants.REFRESH_STATUS_ACTIVE);
        refreshToken.setIssuedAt(newTokens.issuedAt());
        refreshToken.setExpiresAt(newTokens.refreshTokenExpiresAt());
        refreshToken.setRevokedAt(EPOCH);
        refreshToken.setCreatedAt(newTokens.issuedAt());
        refreshToken.setUpdatedAt(newTokens.issuedAt());
        refreshToken.setDeleted(AuthConstants.DELETED_NO);
        refreshTokenMapper.insert(refreshToken);
        cacheRefreshTokenAfterCommit(newTokens.refreshTokenJti(), tokenHash, newTokens.issuedAt(),
                newTokens.refreshTokenExpiresAt());
    }

    /**
     * 将 refresh token secret 的 HMAC hash 写入 Redis，作为后续校验的短路径缓存。
     *
     * @param refreshTokenJti refresh token 的唯一标识
     * @param tokenHash refresh token secret 的 HMAC hash
     * @param now 当前签发时间
     * @param expiresAt refresh token 过期时间
     */
    private void cacheRefreshTokenAfterCommit(String refreshTokenJti, String tokenHash, OffsetDateTime now,
                                              OffsetDateTime expiresAt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cacheRefreshToken(refreshTokenJti, tokenHash, now, expiresAt);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheRefreshToken(refreshTokenJti, tokenHash, now, expiresAt);
            }
        });
    }

    /**
     * 将 refresh token secret 的 HMAC hash 写入 Redis，作为后续校验的短路径缓存。
     *
     * @param refreshTokenJti refresh token 的唯一标识
     * @param tokenHash refresh token secret 的 HMAC hash
     * @param now 当前签发时间
     * @param expiresAt refresh token 过期时间
     */
    private void cacheRefreshToken(String refreshTokenJti, String tokenHash, OffsetDateTime now,
                                   OffsetDateTime expiresAt) {
        Duration ttl = Duration.between(now, expiresAt);
        if (!ttl.isPositive()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(redisKeyFactory.refresh(refreshTokenJti), tokenHash, ttl);
        } catch (Exception exception) {
            log.warn("Refresh token Redis cache write failed, jti={}", refreshTokenJti, exception);
        }
    }

    /**
     * 撤销当前账号、用户和设备下的活跃 refresh token 会话。
     *
     * @param accountId 账号业务 ID
     * @param userId 用户业务 ID
     * @param deviceId 设备业务 ID
     * @return 更新记录数
     */
    @Transactional
    public int revokeActiveSession(long accountId, long userId, long deviceId) {
        OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        GatewayRefreshTokenEntity update = new GatewayRefreshTokenEntity();
        update.setStatus(AuthConstants.REFRESH_STATUS_REVOKED);
        update.setRevokedAt(now);
        update.setUpdatedAt(now);
        return refreshTokenMapper.update(update, new LambdaUpdateWrapper<GatewayRefreshTokenEntity>()
                .eq(GatewayRefreshTokenEntity::getAccountId, accountId)
                .eq(GatewayRefreshTokenEntity::getUserId, userId)
                .eq(GatewayRefreshTokenEntity::getDeviceId, deviceId)
                .eq(GatewayRefreshTokenEntity::getStatus, AuthConstants.REFRESH_STATUS_ACTIVE)
                .eq(GatewayRefreshTokenEntity::getDeleted, AuthConstants.DELETED_NO));
    }

    /**
     * 登录身份最小快照，用于 refresh token 持久化。
     *
     * @param accountId 账号业务 ID
     * @param userId 用户业务 ID
     * @param deviceId 设备业务 ID
     */
    public record AuthServiceLoginIdentity(long accountId, long userId, long deviceId) {
    }
}

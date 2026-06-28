package site.jianjiange.mobilegateway.manager;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.Duration;
import java.time.OffsetDateTime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import site.jianjiange.mobilegateway.entity.GatewayRefreshTokenEntity;
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
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory redisKeyFactory;

    /**
     * 创建 refresh token 管理器。
     *
     * @param refreshTokenMapper refresh token 表 Mapper
     * @param idGenerator 雪花 ID 生成器
     * @param hmacHasher HMAC hash 工具
     * @param redisTemplate Redis 字符串客户端
     * @param redisKeyFactory Redis key 工厂
     */
    public RefreshTokenManager(GatewayRefreshTokenMapper refreshTokenMapper, SnowflakeIdGenerator idGenerator,
                               HmacHasher hmacHasher, StringRedisTemplate redisTemplate,
                               RedisKeyFactory redisKeyFactory) {
        this.refreshTokenMapper = refreshTokenMapper;
        this.idGenerator = idGenerator;
        this.hmacHasher = hmacHasher;
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
        cacheRefreshToken(tokens.refreshTokenJti(), tokenHash, now, tokens.refreshTokenExpiresAt());
    }

    /**
     * 将 refresh token secret 的 HMAC hash 写入 Redis，作为后续校验的短路径缓存。
     *
     * @param refreshTokenJti refresh token 的唯一标识
     * @param tokenHash refresh token secret 的 HMAC hash
     * @param now 当前签发时间
     * @param expiresAt refresh token 过期时间
     */
    private void cacheRefreshToken(String refreshTokenJti, String tokenHash, OffsetDateTime now, OffsetDateTime expiresAt) {
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

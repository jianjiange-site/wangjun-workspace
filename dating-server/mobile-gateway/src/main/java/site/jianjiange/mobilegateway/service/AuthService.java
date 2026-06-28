package site.jianjiange.mobilegateway.service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.jianjiange.mobilegateway.client.UserGrpcClient;
import site.jianjiange.mobilegateway.context.AuthContext;
import site.jianjiange.mobilegateway.entity.GatewayAccountEntity;
import site.jianjiange.mobilegateway.entity.GatewayDeviceEntity;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;
import site.jianjiange.mobilegateway.manager.AuthConstants;
import site.jianjiange.mobilegateway.manager.GatewayAccountManager;
import site.jianjiange.mobilegateway.manager.GatewayDeviceManager;
import site.jianjiange.mobilegateway.manager.RefreshTokenManager;
import site.jianjiange.mobilegateway.support.RedisKeyFactory;
import site.jianjiange.mobilegateway.vo.LoginTokenVO;

/**
 * 认证编排服务，负责本地认证域、user-service 初始化和 token 签发的流程衔接。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final GatewayAccountManager accountManager;
    private final GatewayDeviceManager deviceManager;
    private final UserGrpcClient userGrpcClient;
    private final JwtService jwtService;
    private final RefreshTokenManager refreshTokenManager;
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory redisKeyFactory;

    /**
     * 创建认证编排服务。
     *
     * @param accountManager 账号管理器
     * @param deviceManager 设备管理器
     * @param userGrpcClient user-service gRPC 客户端
     * @param jwtService JWT 签发服务
     * @param refreshTokenManager refresh token 管理器
     * @param redisTemplate Redis 字符串客户端
     * @param redisKeyFactory Redis key 工厂
     */
    public AuthService(GatewayAccountManager accountManager, GatewayDeviceManager deviceManager,
                       UserGrpcClient userGrpcClient, JwtService jwtService,
                       RefreshTokenManager refreshTokenManager, StringRedisTemplate redisTemplate,
                       RedisKeyFactory redisKeyFactory) {
        this.accountManager = accountManager;
        this.deviceManager = deviceManager;
        this.userGrpcClient = userGrpcClient;
        this.jwtService = jwtService;
        this.refreshTokenManager = refreshTokenManager;
        this.redisTemplate = redisTemplate;
        this.redisKeyFactory = redisKeyFactory;
    }

    /**
     * 查找或创建设备登录身份。
     *
     * @param deviceKeyHash 设备标识 HMAC hash
     * @param hashVersion HMAC hash 版本
     * @param deviceName 设备展示名称
     * @param clientType 客户端类型
     * @return 登录身份信息
     */
    @Transactional
    public LoginIdentity findOrCreateDeviceIdentity(String deviceKeyHash, int hashVersion,
                                                    String deviceName, String clientType) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        GatewayDeviceEntity existingDevice = deviceManager.findByDeviceKeyHash(deviceKeyHash);
        if (existingDevice != null) {
            return existingDeviceIdentity(existingDevice, now);
        }
        GatewayAccountEntity account = accountManager.createDeviceAccount(deviceKeyHash, hashVersion, now);
        GatewayDeviceEntity device = deviceManager.createForAccount(account, deviceKeyHash, hashVersion,
                deviceName, clientType, now);
        return toIdentity(account, device);
    }

    /**
     * 设备唯一键并发冲突后重新加载已创建身份。
     *
     * @param deviceKeyHash 设备标识 HMAC hash
     * @return 登录身份信息
     */
    public LoginIdentity loadDeviceIdentityAfterConflict(String deviceKeyHash) {
        GatewayDeviceEntity device = deviceManager.findByDeviceKeyHash(deviceKeyHash);
        if (device == null) {
            throw new DuplicateKeyException("device key conflict but device is not visible yet");
        }
        return existingDeviceIdentity(device, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 确保 user-service 已按 user_id 完成幂等初始化。
     *
     * @param identity 登录身份信息
     */
    public void ensureUserInitialized(LoginIdentity identity) {
        if (identity.userRegisterStatus() == AuthConstants.REGISTER_STATUS_DONE) {
            return;
        }
        userGrpcClient.registerOrInitialize(identity.userId(), identity.accountType(),
                AuthConstants.REGISTER_SOURCE_DEVICE_LOGIN);
        markUserRegisterDone(identity.accountId());
    }

    /**
     * 签发登录 token 并持久化 refresh token 会话。
     *
     * @param identity 登录身份信息
     * @return 登录 token 响应
     */
    public LoginTokenVO issueLoginTokens(LoginIdentity identity) {
        JwtService.IssuedTokens tokens = jwtService.issue(identity.accountId(), identity.userId(), identity.deviceId());
        refreshTokenManager.persistInitial(new RefreshTokenManager.AuthServiceLoginIdentity(
                identity.accountId(), identity.userId(), identity.deviceId()), tokens);
        return new LoginTokenVO(tokens.accessToken(), tokens.refreshToken(), "Bearer",
                tokens.expiresIn(), identity.userId());
    }

    /**
     * 退出当前会话，将当前 access token jti 写入 Redis blacklist，并撤销当前设备下活跃 refresh token。
     *
     * @param authContext 当前认证上下文
     */
    public void logout(AuthContext authContext) {
        if (authContext == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        blacklistAccessToken(authContext);
        refreshTokenManager.revokeActiveSession(authContext.accountId(), authContext.userId(), authContext.deviceId());
    }

    /**
     * 标记账号已完成 user-service 初始化。
     *
     * @param accountId 账号业务 ID
     */
    @Transactional
    public void markUserRegisterDone(long accountId) {
        accountManager.markRegisterDone(accountId, OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 将 access token jti 写入 Redis blacklist，TTL 等于 access token 剩余有效期。
     *
     * @param authContext 当前认证上下文
     */
    private void blacklistAccessToken(AuthContext authContext) {
        Duration ttl = Duration.between(Instant.now(), authContext.accessTokenExpiresAt());
        if (!ttl.isPositive()) {
            return;
        }
        String redisKey = redisKeyFactory.jwtBlacklist(authContext.accessTokenJti());
        try {
            redisTemplate.opsForValue().set(redisKey, "1", ttl);
        } catch (Exception exception) {
            log.warn("JWT blacklist Redis write failed, jti={}", authContext.accessTokenJti(), exception);
            throw new BusinessException(ResultCode.TOKEN_BLACKLIST_UNAVAILABLE);
        }
    }

    /**
     * 根据已存在设备恢复登录身份，并更新设备最近活跃和账号最近登录时间。
     *
     * @param device 已存在的设备记录
     * @param now 当前业务时间
     * @return 登录身份信息
     */
    private LoginIdentity existingDeviceIdentity(GatewayDeviceEntity device, OffsetDateTime now) {
        if (device.getStatus() == AuthConstants.STATUS_DISABLED) {
            throw new BusinessException(ResultCode.DEVICE_DISABLED);
        }
        GatewayAccountEntity account = accountManager.findByAccountId(device.getAccountId());
        if (account == null) {
            throw new BusinessException(ResultCode.ACCOUNT_NOT_FOUND);
        }
        if (account.getStatus() == AuthConstants.STATUS_DISABLED) {
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }
        deviceManager.touchLastSeen(device.getDeviceId(), now);
        accountManager.touchLastLogin(account.getAccountId(), now);
        return toIdentity(account, device);
    }

    /**
     * 将账号和设备实体压缩为后续认证流程需要的登录身份快照。
     *
     * @param account 网关账号实体
     * @param device 网关设备实体
     * @return 登录身份信息
     */
    private LoginIdentity toIdentity(GatewayAccountEntity account, GatewayDeviceEntity device) {
        return new LoginIdentity(account.getAccountId(), account.getUserId(), device.getDeviceId(),
                account.getAccountType(), account.getUserRegisterStatus());
    }

    /**
     * 认证登录身份快照。
     *
     * @param accountId 账号业务 ID
     * @param userId 用户业务 ID
     * @param deviceId 设备业务 ID
     * @param accountType 账号类型
     * @param userRegisterStatus user-service 初始化状态
     */
    public record LoginIdentity(long accountId, long userId, long deviceId, String accountType,
                                int userRegisterStatus) {
    }
}

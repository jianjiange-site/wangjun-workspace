package site.jianjiange.mobilegateway.support;

import java.util.Arrays;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import site.jianjiange.mobilegateway.config.RedisConfig;

/**
 * Redis key 工厂，统一添加业务前缀并规范化 key 片段。
 */
@Component
public class RedisKeyFactory {

    private final RedisConfig config;

    /**
     * 创建 Redis key 工厂。
     *
     * @param config Redis 业务配置
     */
    public RedisKeyFactory(RedisConfig config) {
        this.config = config;
    }

    /**
     * 使用统一前缀拼接 Redis key。
     *
     * @param parts key 片段
     * @return 带统一前缀的 Redis key
     */
    public String key(String... parts) {
        String suffix = Arrays.stream(parts)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(this::stripEdgeColon)
                .reduce((left, right) -> left + ":" + right)
                .orElseThrow(() -> new IllegalArgumentException("Redis key parts must not be empty"));
        return stripTrailingColon(config.getKeyPrefix()) + ":" + suffix;
    }

    /**
     * 构造滑动窗口限流 key。
     *
     * @param dimension 限流维度
     * @param key 限流对象
     * @return 限流 Redis key
     */
    public String rateLimit(String dimension, String key) {
        return key("ratelimit", dimension, key);
    }

    /**
     * 构造手机验证码 key。
     *
     * @param phoneHash 手机号 HMAC hash
     * @return 手机验证码 Redis key
     */
    public String smsCode(String phoneHash) {
        return key("sms-code", phoneHash);
    }

    /**
     * 构造手机验证码发送冷却 key。
     *
     * @param phoneHash 手机号 HMAC hash
     * @return 手机验证码冷却 Redis key
     */
    public String smsCooldown(String phoneHash) {
        return key("sms-cooldown", phoneHash);
    }

    /**
     * 构造登录失败计数 key。
     *
     * @param type 登录类型
     * @param principalHash 登录主体 HMAC hash
     * @return 登录失败计数 Redis key
     */
    public String loginFail(String type, String principalHash) {
        return key("login-fail", type, principalHash);
    }

    /**
     * 构造 access token 黑名单 key。
     *
     * @param jti token 唯一标识
     * @return JWT 黑名单 Redis key
     */
    public String jwtBlacklist(String jti) {
        return key("jwt", "blacklist", jti);
    }

    /**
     * 构造 refresh token 会话缓存 key。
     *
     * @param jti refresh token 唯一标识
     * @return refresh token Redis key
     */
    public String refresh(String jti) {
        return key("refresh", jti);
    }

    /**
     * 构造 Google JWKS 缓存 key。
     *
     * @param kid Google key id
     * @return Google JWKS Redis key
     */
    public String googleJwks(String kid) {
        return key("google", "jwks", kid);
    }

    /**
     * 构造账号短缓存 key。
     *
     * @param accountId 账号业务 ID
     * @return 账号缓存 Redis key
     */
    public String account(long accountId) {
        return key("account", String.valueOf(accountId));
    }

    /**
     * 构造设备短缓存 key。
     *
     * @param deviceId 设备业务 ID
     * @return 设备缓存 Redis key
     */
    public String device(long deviceId) {
        return key("device", String.valueOf(deviceId));
    }

    /**
     * 移除配置前缀末尾多余冒号。
     *
     * @param value 配置值
     * @return 规范化后的前缀
     */
    private String stripTrailingColon(String value) {
        String result = value.trim();
        while (result.endsWith(":")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 移除 key 片段首尾多余冒号。
     *
     * @param value key 片段
     * @return 规范化后的 key 片段
     */
    private String stripEdgeColon(String value) {
        String result = value;
        while (result.startsWith(":")) {
            result = result.substring(1);
        }
        while (result.endsWith(":")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}

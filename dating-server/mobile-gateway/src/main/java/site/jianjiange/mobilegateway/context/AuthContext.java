package site.jianjiange.mobilegateway.context;

import java.time.Instant;

/**
 * 当前请求的认证上下文。
 *
 * @param userId 用户业务 ID
 * @param accountId 账号业务 ID
 * @param deviceId 设备业务 ID
 * @param traceId 请求链路 ID
 * @param accessTokenJti access token 唯一标识
 * @param accessTokenExpiresAt access token 过期时间
 */
public record AuthContext(long userId, long accountId, long deviceId, String traceId,
                          String accessTokenJti, Instant accessTokenExpiresAt) {
}

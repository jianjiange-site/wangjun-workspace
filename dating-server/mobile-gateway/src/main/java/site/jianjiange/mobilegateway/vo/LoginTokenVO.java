package site.jianjiange.mobilegateway.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 登录成功后的 token 响应对象。
 *
 * @param accessToken access token 明文
 * @param refreshToken refresh token 明文
 * @param tokenType token 类型，固定为 Bearer
 * @param expiresIn access token 有效期秒数
 * @param userId 当前登录用户业务 ID
 */
public record LoginTokenVO(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("user_id") long userId
) {
}

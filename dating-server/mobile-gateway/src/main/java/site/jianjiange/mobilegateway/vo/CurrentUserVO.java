package site.jianjiange.mobilegateway.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 当前用户资料响应对象。
 *
 * @param userId 用户业务 ID
 * @param nickname 用户昵称
 * @param avatarUrl 头像 URL
 * @param bio 个人简介
 */
public record CurrentUserVO(
        @JsonProperty("user_id") long userId,
        String nickname,
        @JsonProperty("avatar_url") String avatarUrl,
        String bio
) {
}

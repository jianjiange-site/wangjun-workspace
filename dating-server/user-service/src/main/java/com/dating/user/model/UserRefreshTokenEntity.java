package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("user_refresh_token")
public class UserRefreshTokenEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private String deviceId;
    private String refreshTokenHash;

    private Instant expireAt;
    private Boolean revoked;

    private Instant createdAt;
    private Instant updatedAt;
}

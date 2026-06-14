package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("login_session")
public class UserSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    private String userId;
    private String loginType;
    private String deviceId;

    private String status;
    private Boolean livenessRequired;
    private String livenessStatus;

    private String failReason;
    private Instant expireAt;

    private Instant createdAt;
    private Instant updatedAt;
}

package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("user_device")
public class UserDeviceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private String deviceId;
    private String deviceType;
    private String deviceName;
    private String appVersion;

    private String lastLoginIp;
    private Instant lastLoginAt;

    private Boolean trusted;

    private Instant createdAt;
    private Instant updatedAt;

    @TableLogic
    private Boolean deleted;
}

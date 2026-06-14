package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("users")
public class UserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private String userType;
    private String status;

    private String phoneCountryCode;
    private String phone;

    private Boolean profileCompleted;
    private String livenessStatus;

    private Instant lastLoginAt;
    private Instant createdAt;
    private Instant updatedAt;

    @TableLogic
    private Boolean deleted;
}

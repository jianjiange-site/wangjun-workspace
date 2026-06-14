package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("user_auth_identities")
public class UserAuthIdentityEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private String identityType;
    private String provider;
    private String identityId;

    private Instant createdAt;
    private Instant updatedAt;

    @TableLogic
    private Boolean deleted;
}

package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("sms_code")
public class SmsCodeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String phoneCountryCode;
    private String phone;
    private String scene;

    private String codeHash;
    private Instant expireAt;
    private Boolean used;

    private String sendIp;
    private Instant createdAt;
}

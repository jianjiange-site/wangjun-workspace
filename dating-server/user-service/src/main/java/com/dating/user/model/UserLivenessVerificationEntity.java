package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@TableName("liveness_record")
public class UserLivenessVerificationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String livenessId;
    private String sessionId;
    private String userId;

    private String provider;
    private String providerRequestId;
    private String status;

    private BigDecimal score;
    private String failReason;
    private String rawResult;

    private Instant createdAt;
    private Instant updatedAt;
}

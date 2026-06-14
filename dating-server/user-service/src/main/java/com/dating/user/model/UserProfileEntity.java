package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Data
@TableName("user_profile")
public class UserProfileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private String nickname;
    private String gender;
    private LocalDate birthDate;
    private String raceCode;

    private String avatarObjectKey;
    private String avatarUrl;
    private String avatarAuditStatus;
    private Boolean isRealPerson;
    private BigDecimal faceScore;

    private String cityCode;
    private String cityName;
    private String bio;
    private String occupation;
    private Integer heightCm;
    private Integer weightKg;
    private String education;
    private String mbti;

    private Instant createdAt;
    private Instant updatedAt;

    @TableLogic
    private Boolean deleted;
}

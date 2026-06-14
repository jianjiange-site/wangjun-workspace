package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("user_tag")
public class UserTagEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private String tagId;

    private Instant createdAt;
}

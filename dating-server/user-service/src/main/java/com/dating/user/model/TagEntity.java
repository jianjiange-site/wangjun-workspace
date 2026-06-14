package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("tags")
public class TagEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tagId;
    private String category;
    private String name;
    private String status;

    private Instant createdAt;
    private Instant updatedAt;

    @TableLogic
    private Boolean deleted;
}

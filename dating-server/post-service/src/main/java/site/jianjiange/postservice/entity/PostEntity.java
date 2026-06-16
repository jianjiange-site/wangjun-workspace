package site.jianjiange.postservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;
import site.jianjiange.postservice.enums.PostStatus;

/**
 * 帖子主表实体，映射数据库 post 表。
 */
@Data
@TableName("post")
public class PostEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long postNo;
    private Long authorId;
    private String content;
    private Integer imageCount;
    private PostStatus status;
    private Long likeCount;
    private Long commentCount;
    private OffsetDateTime publishedAt;
    private OffsetDateTime deletedAt;
    private Integer version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

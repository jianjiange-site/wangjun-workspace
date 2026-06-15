package site.jianjiange.postservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 帖子点赞关系实体，用于记录用户对帖子的唯一点赞关系。
 */
@Data
@TableName("post_like")
public class PostLikeEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long postId;
    private Long postAuthorId;
    private OffsetDateTime createdAt;
}

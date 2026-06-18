package site.jianjiange.postservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;
import site.jianjiange.postservice.enums.CommentStatus;

/**
 * 评论实体，支持一级评论和二级回复的数据映射。
 */
@Data
@TableName("comment")
public class CommentEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long commentNo;
    private Long postNo;
    private Long authorId;
    private Long rootCommentNo;
    private Long parentCommentNo;
    private Long replyToUserId;
    private String content;
    private Integer level;
    private CommentStatus status;
    private OffsetDateTime createdAt;
    private OffsetDateTime deletedAt;
}

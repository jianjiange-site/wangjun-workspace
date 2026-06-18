package site.jianjiange.postservice.cache;

import java.time.OffsetDateTime;
import site.jianjiange.postservice.entity.CommentEntity;
import site.jianjiange.postservice.enums.CommentStatus;

/**
 * Redis 中等待回写的评论事实。
 *
 * @param id 评论技术主键，创建请求时提前生成
 * @param commentNo 评论业务号
 * @param postNo 帖子业务号
 * @param authorId 评论作者 ID
 * @param rootCommentNo 一级评论业务号
 * @param parentCommentNo 被回复评论业务号
 * @param replyToUserId 被回复用户 ID
 * @param content 评论正文
 * @param level 展示层级，1 为一级评论，2 为二级回复
 * @param status 评论状态
 * @param createdAt 创建时间
 * @param deletedAt 删除时间，未删除时为哨兵时间
 * @param persisted 删除事实接收前是否已经写入数据库并计入 post.comment_count
 */
public record PendingCommentRecord(
        Long id,
        Long commentNo,
        Long postNo,
        Long authorId,
        Long rootCommentNo,
        Long parentCommentNo,
        Long replyToUserId,
        String content,
        Integer level,
        CommentStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt,
        boolean persisted
) {

    /**
     * 将 Redis pending 事实转换为数据库实体，供异步回写复用。
     *
     * @return 评论实体
     */
    public CommentEntity toEntity() {
        CommentEntity entity = new CommentEntity();
        entity.setId(id);
        entity.setCommentNo(commentNo);
        entity.setPostNo(postNo);
        entity.setAuthorId(authorId);
        entity.setRootCommentNo(rootCommentNo);
        entity.setParentCommentNo(parentCommentNo);
        entity.setReplyToUserId(replyToUserId);
        entity.setContent(content);
        entity.setLevel(level);
        entity.setStatus(status);
        entity.setCreatedAt(createdAt);
        entity.setDeletedAt(deletedAt);
        return entity;
    }
}

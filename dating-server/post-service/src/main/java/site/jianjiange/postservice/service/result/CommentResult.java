package site.jianjiange.postservice.service.result;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 评论业务结果；无父评论或无被回复人时返回 -1。
 */
public record CommentResult(
        Long commentNo,
        Long postNo,
        Long authorId,
        Long rootCommentNo,
        Long parentCommentNo,
        Long replyToUserId,
        String content,
        Integer level,
        OffsetDateTime createdAt,
        Long replyTotalCount,
        Integer replyPageSize,
        Integer replyTotalPages,
        List<CommentResult> replies
) {
}

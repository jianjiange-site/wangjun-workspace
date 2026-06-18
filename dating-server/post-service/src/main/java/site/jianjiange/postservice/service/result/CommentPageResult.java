package site.jianjiange.postservice.service.result;

import java.time.OffsetDateTime;
import java.util.List;
import site.jianjiange.postservice.constant.DatabaseSentinel;

/**
 * 评论分页结果；一级评论使用游标字段，二级回复继续使用页号字段。
 */
public record CommentPageResult(
        List<CommentResult> comments,
        Integer pageNo,
        Integer pageSize,
        Long totalCount,
        Integer totalPages,
        boolean hasPrevious,
        boolean hasNext,
        OffsetDateTime nextCursorCreatedAt,
        Long nextCursorCommentNo
) {
    public CommentPageResult(
            List<CommentResult> comments,
            Integer pageNo,
            Integer pageSize,
            Long totalCount,
            Integer totalPages,
            boolean hasPrevious,
            boolean hasNext) {
        this(
                comments,
                pageNo,
                pageSize,
                totalCount,
                totalPages,
                hasPrevious,
                hasNext,
                DatabaseSentinel.NONE_TIME,
                DatabaseSentinel.NONE_ID);
    }
}

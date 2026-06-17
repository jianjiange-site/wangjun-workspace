package site.jianjiange.postservice.service.result;

import java.util.List;

/**
 * 评论页号分页结果，页号从 1 开始。
 */
public record CommentPageResult(
        List<CommentResult> comments,
        Integer pageNo,
        Integer pageSize,
        Long totalCount,
        Integer totalPages,
        boolean hasPrevious,
        boolean hasNext
) {
}

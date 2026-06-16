package site.jianjiange.postservice.service.result;

import java.time.OffsetDateTime;
import java.util.List;
import site.jianjiange.postservice.enums.PostStatus;

/**
 * 帖子业务结果，供详情、作者列表和后续 gRPC 出参转换使用。
 */
public record PostResult(
        Long postNo,
        Long authorId,
        String content,
        Integer imageCount,
        PostStatus status,
        Long likeCount,
        Long commentCount,
        OffsetDateTime publishedAt,
        List<PostImageResult> images) {
}

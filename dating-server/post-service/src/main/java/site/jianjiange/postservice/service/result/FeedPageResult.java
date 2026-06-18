package site.jianjiange.postservice.service.result;

import java.util.List;

/**
 * Feed 分页结果，固定 pageSize 为 20。
 *
 * @param posts Feed 帖子列表
 * @param nextCursor 下一页 cursor
 * @param pageSize 固定页大小
 * @param hasNext 是否可能还有下一页
 */
public record FeedPageResult(
        List<PostResult> posts,
        String nextCursor,
        Integer pageSize,
        boolean hasNext
) {
}

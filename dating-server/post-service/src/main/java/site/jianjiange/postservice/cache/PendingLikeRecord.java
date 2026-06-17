package site.jianjiange.postservice.cache;

/**
 * Redis 中等待计数回写的点赞记录。
 *
 * @param userId 点赞用户 ID
 * @param postNo 帖子业务号
 */
public record PendingLikeRecord(
        Long userId,
        Long postNo
) {
}

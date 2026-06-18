package site.jianjiange.postservice.cache;

/**
 * Redis 接收评论 pending 写入后的结果。
 *
 * @param commentNo 评论业务号
 * @param duplicated 是否命中同一幂等请求
 * @param conflict 是否发生幂等请求内容冲突
 */
public record PendingCommentAcceptResult(
        Long commentNo,
        boolean duplicated,
        boolean conflict
) {
}

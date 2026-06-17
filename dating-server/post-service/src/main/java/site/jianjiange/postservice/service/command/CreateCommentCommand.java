package site.jianjiange.postservice.service.command;

/**
 * 创建评论命令，parentCommentNo 为 -1 时创建一级评论，否则创建二级回复。
 *
 * @param authorId 当前用户 ID
 * @param postNo 帖子业务号
 * @param parentCommentNo 被回复评论业务号，一级评论传 -1
 * @param content 评论正文
 * @param clientRequestId 客户端幂等请求号
 */
public record CreateCommentCommand(
        Long authorId,
        Long postNo,
        Long parentCommentNo,
        String content,
        String clientRequestId
) {
}

package site.jianjiange.postservice.service.command;

/**
 * 删除评论命令。
 *
 * @param operatorId 当前操作用户 ID
 * @param commentNo 评论业务号
 */
public record DeleteCommentCommand(
        Long operatorId,
        Long commentNo
) {
}

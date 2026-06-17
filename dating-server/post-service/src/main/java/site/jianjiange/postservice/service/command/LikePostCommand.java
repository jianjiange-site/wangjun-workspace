package site.jianjiange.postservice.service.command;

/**
 * 点赞帖子命令，承载当前用户和目标帖子业务号。
 *
 * @param userId 当前用户 ID
 * @param postNo 帖子业务号
 */
public record LikePostCommand(
        Long userId,
        Long postNo
) {
}

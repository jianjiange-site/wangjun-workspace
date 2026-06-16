package site.jianjiange.postservice.service.command;

import java.util.List;

/**
 * 创建帖子命令，承载当前用户、正文、待绑定图片业务号和客户端幂等请求号。
 */
public record CreatePostCommand(
        Long authorId,
        String content,
        List<Long> imageNos,
        String clientRequestId) {
}

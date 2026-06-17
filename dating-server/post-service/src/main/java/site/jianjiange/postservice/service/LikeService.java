package site.jianjiange.postservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import site.jianjiange.postservice.cache.LikeCountCache;
import site.jianjiange.postservice.cache.PendingLikeRecord;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.enums.PostStatus;
import site.jianjiange.postservice.exception.BusinessException;
import site.jianjiange.postservice.exception.PostErrorCode;
import site.jianjiange.postservice.manager.LikeManager;
import site.jianjiange.postservice.service.command.LikePostCommand;
import site.jianjiange.postservice.service.result.LikePostResult;

/**
 * 点赞业务服务，负责编排帖子可见性校验、Redis TTL 去重和异步计数回写。
 */
@Service
public class LikeService {

    private static final Logger log = LoggerFactory.getLogger(LikeService.class);

    private final LikeManager likeManager;
    private final LikeCountCache likeCountCache;

    /**
     * 创建点赞业务服务。
     *
     * @param likeManager 点赞数据管理器
     * @param likeCountCache 点赞计数缓存
     */
    public LikeService(
            LikeManager likeManager,
            LikeCountCache likeCountCache) {
        this.likeManager = likeManager;
        this.likeCountCache = likeCountCache;
    }

    /**
     * 点赞帖子；请求链路只写 Redis，TTL 窗口内重复点赞返回幂等成功。
     *
     * @param command 点赞帖子命令
     * @return 点赞结果
     */
    public LikePostResult likePost(LikePostCommand command) {
        validateLikePostCommand(command);
        PostEntity post = findPublishedPost(command.postNo());
        boolean accepted = acceptLike(command.userId(), post);
        return new LikePostResult(post.getPostNo(), !accepted);
    }

    /**
     * 查询并校验可点赞帖子。
     *
     * @param postNo 帖子业务号
     * @return 公开可见帖子
     */
    private PostEntity findPublishedPost(Long postNo) {
        PostEntity post = likeManager.findPostByPostNo(postNo);
        if (post == null) {
            throw new BusinessException(PostErrorCode.POST_NOT_FOUND, "帖子不存在");
        }
        if (post.getStatus() != PostStatus.PUBLISHED) {
            throw new BusinessException(PostErrorCode.POST_NOT_PUBLISHED, "只能点赞公开可见帖子");
        }
        return post;
    }

    /**
     * 将点赞写入 Redis TTL 去重 key 和实时 delta。
     *
     * @param userId 点赞用户 ID
     * @param post 帖子实体
     * @return 首次点赞返回 true，重复点赞返回 false
     */
    private boolean acceptLike(Long userId, PostEntity post) {
        try {
            PendingLikeRecord record = new PendingLikeRecord(
                    userId,
                    post.getPostNo());
            return likeCountCache.acceptLike(record);
        } catch (RuntimeException ex) {
            log.warn("点赞 Redis 写入失败，postNo={}, userId={}, errorType={}, errorMessage={}",
                    post.getPostNo(), userId, ex.getClass().getSimpleName(), ex.getMessage());
            throw new BusinessException(PostErrorCode.LIKE_TEMPORARILY_UNAVAILABLE, "点赞服务暂时不可用，请稍后重试");
        }
    }

    /**
     * 校验点赞命令参数。
     *
     * @param command 点赞帖子命令
     */
    private void validateLikePostCommand(LikePostCommand command) {
        if (command == null) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "点赞命令不能为空");
        }
        validatePositive(command.userId(), "userId");
        validatePositive(command.postNo(), "postNo");
    }

    /**
     * 校验正整数参数。
     *
     * @param value 参数值
     * @param name 参数名
     */
    private void validatePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, name + " 必须为正整数");
        }
    }

}

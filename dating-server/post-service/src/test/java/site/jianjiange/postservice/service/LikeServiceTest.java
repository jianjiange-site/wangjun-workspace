package site.jianjiange.postservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.ActiveProfiles;
import site.jianjiange.postservice.cache.LikeCountCache;
import site.jianjiange.postservice.cache.PendingLikeRecord;
import site.jianjiange.postservice.constant.DatabaseSentinel;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.enums.PostStatus;
import site.jianjiange.postservice.exception.BusinessException;
import site.jianjiange.postservice.exception.PostErrorCode;
import site.jianjiange.postservice.mapper.PostMapper;
import site.jianjiange.postservice.service.command.LikePostCommand;
import site.jianjiange.postservice.service.result.LikePostResult;

/**
 * 点赞业务服务测试，覆盖 Redis 优先点赞、TTL 去重和异步计数回写规则。
 */
@ActiveProfiles("test")
@SpringBootTest
class LikeServiceTest {

    @Autowired
    private LikeService likeService;

    @Autowired
    private PostMapper postMapper;

    @MockBean
    private LikeCountCache likeCountCache;

    /**
     * 清理帖子，并配置 Redis mock 默认成功。
     */
    @BeforeEach
    void cleanDatabase() {
        postMapper.delete(new QueryWrapper<>());
        when(likeCountCache.acceptLike(any(PendingLikeRecord.class))).thenReturn(true);
    }

    /**
     * 验证首次点赞只写 Redis delta，不在请求链路同步增加数据库计数。
     */
    @Test
    void likePostWritesRedisPendingLikeWithoutDatabaseRelation() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);

        LikePostResult result = likeService.likePost(new LikePostCommand(3001L, 91001L));

        assertThat(result.postNo()).isEqualTo(91001L);
        assertThat(result.duplicated()).isFalse();
        assertThat(postMapper.selectById(1001L).getLikeCount()).isZero();
        verify(likeCountCache).acceptLike(new PendingLikeRecord(3001L, 91001L));
    }

    /**
     * 验证 TTL 窗口内重复点赞由 Redis 去重后返回幂等成功，且请求链路不改数据库计数。
     */
    @Test
    void likePostReturnsIdempotentSuccessForDuplicateLike() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);
        when(likeCountCache.acceptLike(any(PendingLikeRecord.class)))
                .thenReturn(true)
                .thenReturn(false);

        LikePostResult first = likeService.likePost(new LikePostCommand(3001L, 91001L));
        LikePostResult second = likeService.likePost(new LikePostCommand(3001L, 91001L));

        assertThat(first.duplicated()).isFalse();
        assertThat(second.duplicated()).isTrue();
        assertThat(second.postNo()).isEqualTo(91001L);
        assertThat(postMapper.selectById(1001L).getLikeCount()).isZero();
    }

    /**
     * 验证不可见帖子不能点赞。
     */
    @Test
    void likePostRejectsUnpublishedPost() {
        insertPost(1001L, 91001L, 2001L, PostStatus.USER_DELETED);

        assertThatThrownBy(() -> likeService.likePost(new LikePostCommand(3001L, 91001L)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.POST_NOT_PUBLISHED));

        verify(likeCountCache, never()).acceptLike(any(PendingLikeRecord.class));
    }

    /**
     * 验证不存在的帖子不能点赞。
     */
    @Test
    void likePostRejectsMissingPost() {
        assertThatThrownBy(() -> likeService.likePost(new LikePostCommand(3001L, 91001L)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.POST_NOT_FOUND));

        verify(likeCountCache, never()).acceptLike(any(PendingLikeRecord.class));
    }

    /**
     * 验证 Redis 不可用时点赞失败并保护数据库不被请求链路压垮。
     */
    @Test
    void likePostFailsFastWhenRedisUnavailable() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);
        when(likeCountCache.acceptLike(any(PendingLikeRecord.class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThatThrownBy(() -> likeService.likePost(new LikePostCommand(3001L, 91001L)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.LIKE_TEMPORARILY_UNAVAILABLE));

        assertThat(postMapper.selectById(1001L).getLikeCount()).isZero();
    }

    /**
     * 插入用于测试的帖子。
     *
     * @param id 帖子技术主键
     * @param postNo 帖子业务号
     * @param authorId 作者 ID
     * @param status 帖子状态
     */
    private void insertPost(Long id, Long postNo, Long authorId, PostStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        PostEntity post = new PostEntity();
        post.setId(id);
        post.setPostNo(postNo);
        post.setAuthorId(authorId);
        post.setContent("hello post");
        post.setImageCount(0);
        post.setStatus(status);
        post.setLikeCount(0L);
        post.setCommentCount(0L);
        post.setPublishedAt(now);
        post.setDeletedAt(DatabaseSentinel.NONE_TIME);
        post.setVersion(0);
        post.setCreatedAt(now);
        post.setUpdatedAt(now);
        postMapper.insert(post);
    }
}

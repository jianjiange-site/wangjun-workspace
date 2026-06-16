package site.jianjiange.postservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import site.jianjiange.postservice.entity.IdempotentRequestEntity;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.entity.PostImageEntity;
import site.jianjiange.postservice.enums.ImageStatus;
import site.jianjiange.postservice.enums.PostStatus;
import site.jianjiange.postservice.exception.BusinessException;
import site.jianjiange.postservice.exception.PostErrorCode;
import site.jianjiange.postservice.mapper.IdempotentRequestMapper;
import site.jianjiange.postservice.mapper.PostImageMapper;
import site.jianjiange.postservice.mapper.PostMapper;
import site.jianjiange.postservice.service.command.CreatePostCommand;
import site.jianjiange.postservice.service.result.CreatePostResult;
import site.jianjiange.postservice.service.result.PostResult;

/**
 * 帖子业务服务测试，覆盖阶段 4.1 的创建、删除、详情和作者列表。
 */
@ActiveProfiles("test")
@SpringBootTest
class PostServiceTest {

    @Autowired
    private PostService postService;

    @Autowired
    private PostMapper postMapper;

    @SpyBean
    private PostImageMapper postImageMapper;

    @Autowired
    private IdempotentRequestMapper idempotentRequestMapper;

    /**
     * 清理帖子、图片和幂等记录，保证每个用例独立。
     */
    @BeforeEach
    void cleanDatabase() {
        idempotentRequestMapper.delete(new QueryWrapper<>());
        postImageMapper.delete(new QueryWrapper<>());
        postMapper.delete(new QueryWrapper<>());
    }

    /**
     * 验证创建帖子后状态为 PUBLISHED，图片被绑定，并写入幂等记录。
     */
    @Test
    void createPostPublishesPostAndBindsImages() {
        insertTempImage(7001L, 9001L);
        insertTempImage(7002L, 9001L);

        CreatePostResult result = postService.createPost(
                new CreatePostCommand(9001L, " hello post ", List.of(7001L, 7002L), "req-create-1"));

        PostEntity post = selectByPostNo(result.postNo());
        assertThat(result.duplicated()).isFalse();
        assertThat(post.getId()).isNotEqualTo(post.getPostNo());
        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(post.getContent()).isEqualTo("hello post");
        assertThat(post.getImageCount()).isEqualTo(2);
        assertThat(postImageMapper.selectList(new LambdaQueryWrapper<PostImageEntity>()
                .eq(PostImageEntity::getPostId, post.getId())
                .orderByAsc(PostImageEntity::getSortOrder)))
                .extracting(PostImageEntity::getImageNo)
                .containsExactly(7001L, 7002L);
        assertThat(idempotentRequestMapper.selectList(new LambdaQueryWrapper<IdempotentRequestEntity>()
                .eq(IdempotentRequestEntity::getUserId, 9001L)
                .eq(IdempotentRequestEntity::getClientRequestId, "req-create-1")))
                .extracting(IdempotentRequestEntity::getBizNo)
                .containsExactly(result.postNo());
        assertThat(postImageMapper.selectList(new LambdaQueryWrapper<PostImageEntity>()
                .eq(PostImageEntity::getPostId, post.getId())))
                .extracting(PostImageEntity::getStatus)
                .containsExactly(ImageStatus.BOUND, ImageStatus.BOUND);
    }

    /**
     * 验证重复创建同一个幂等请求时返回原帖子 ID，不会新建帖子。
     */
    @Test
    void createPostReturnsExistingResultForSameIdempotentRequest() {
        CreatePostResult first = postService.createPost(
                new CreatePostCommand(9001L, "hello post", List.of(), "req-create-2"));

        CreatePostResult second = postService.createPost(
                new CreatePostCommand(9001L, "hello post", List.of(), "req-create-2"));

        assertThat(second.postNo()).isEqualTo(first.postNo());
        assertThat(second.duplicated()).isTrue();
        assertThat(postMapper.selectCount(new QueryWrapper<>())).isEqualTo(1);
    }

    /**
     * 验证同一幂等请求号对应不同请求内容时拒绝处理。
     */
    @Test
    void createPostRejectsChangedPayloadForSameIdempotentRequest() {
        postService.createPost(new CreatePostCommand(9001L, "hello post", List.of(), "req-create-3"));

        assertThatThrownBy(() -> postService.createPost(
                new CreatePostCommand(9001L, "changed post", List.of(), "req-create-3")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.IDEMPOTENT_CONFLICT));
    }

    /**
     * 验证图片绑定时如果状态被并发修改，创建帖子会失败并回滚。
     */
    @Test
    void createPostRollsBackWhenImageBindingStateChangedConcurrently() {
        insertTempImage(7301L, 9001L);
        doReturn(0).when(postImageMapper).update(isNull(), any());

        assertThatThrownBy(() -> postService.createPost(
                new CreatePostCommand(9001L, "hello post", List.of(7301L), "req-bind-race-1")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.IMAGE_STATUS_INVALID));

        assertThat(postMapper.selectCount(new QueryWrapper<>())).isZero();
        assertThat(idempotentRequestMapper.selectCount(new QueryWrapper<>())).isZero();
    }

    /**
     * 验证作者只能删除自己的帖子，删除后普通详情和作者列表不可见。
     */
    @Test
    void deletePostOnlyAllowsAuthorAndHidesPost() {
        CreatePostResult result = postService.createPost(
                new CreatePostCommand(9001L, "hello post", List.of(), "req-delete-1"));

        assertThatThrownBy(() -> postService.deletePost(9002L, result.postNo()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.POST_FORBIDDEN));

        postService.deletePost(9001L, result.postNo());

        assertThat(selectByPostNo(result.postNo()).getStatus()).isEqualTo(PostStatus.USER_DELETED);
        assertThat(postService.getPostDetail(result.postNo())).isEmpty();
        assertThat(postService.listAuthorPosts(9001L, 20)).isEmpty();
    }

    /**
     * 验证详情和作者列表只返回公开可见帖子。
     */
    @Test
    void getDetailAndAuthorListOnlyReturnPublishedPosts() {
        CreatePostResult first = postService.createPost(
                new CreatePostCommand(9001L, "first post", List.of(), "req-list-1"));
        CreatePostResult second = postService.createPost(
                new CreatePostCommand(9001L, "second post", List.of(), "req-list-2"));
        postService.createPost(new CreatePostCommand(9002L, "other post", List.of(), "req-list-3"));
        postService.deletePost(9001L, first.postNo());

        assertThat(postService.getPostDetail(first.postNo())).isEmpty();
        assertThat(postService.getPostDetail(second.postNo())).isPresent();
        assertThat(postService.listAuthorPosts(9001L, 20))
                .extracting(result -> result.postNo())
                .containsExactly(second.postNo());
    }

    /**
     * 验证作者帖子列表会批量加载图片，避免每条帖子单独查询图片形成 N+1。
     */
    @Test
    void listAuthorPostsLoadsImagesInBatch() {
        insertTempImage(7101L, 9001L);
        insertTempImage(7201L, 9001L);
        CreatePostResult first = postService.createPost(
                new CreatePostCommand(9001L, "first post", List.of(7101L), "req-batch-1"));
        CreatePostResult second = postService.createPost(
                new CreatePostCommand(9001L, "second post", List.of(7201L), "req-batch-2"));

        clearInvocations(postImageMapper);
        List<PostResult> posts = postService.listAuthorPosts(9001L, 20);

        verify(postImageMapper, times(1)).selectList(any());
        assertThat(posts).hasSize(2);
        assertThat(findPostResult(posts, first.postNo()).images())
                .extracting(image -> image.imageNo())
                .containsExactly(7101L);
        assertThat(findPostResult(posts, second.postNo()).images())
                .extracting(image -> image.imageNo())
                .containsExactly(7201L);
    }

    /**
     * 插入当前用户的 TEMP 图片。
     *
     * @param imageNo 图片业务号
     * @param userId 用户 ID
     */
    private void insertTempImage(Long imageNo, Long userId) {
        OffsetDateTime now = OffsetDateTime.now();
        PostImageEntity image = new PostImageEntity();
        image.setId(100000L + imageNo);
        image.setImageNo(imageNo);
        image.setUserId(userId);
        image.setBucket("wangjun-dating");
        image.setObjectKey("wangjun-tmp/post/" + userId + "/" + imageNo + ".jpg");
        image.setContentType("image/jpeg");
        image.setSizeBytes(1024L);
        image.setEtag("etag-" + imageNo);
        image.setWidth(640);
        image.setHeight(480);
        image.setSortOrder(0);
        image.setStatus(ImageStatus.TEMP);
        image.setUploadExpireAt(now.plusHours(1));
        image.setRetryCount(0);
        image.setCreatedAt(now);
        image.setUpdatedAt(now);
        postImageMapper.insert(image);
    }

    /**
     * 按帖子业务号查询帖子实体。
     *
     * @param postNo 帖子业务号
     * @return 帖子实体
     */
    private PostEntity selectByPostNo(Long postNo) {
        return postMapper.selectOne(new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getPostNo, postNo));
    }

    /**
     * 从帖子结果列表中查找指定业务号的帖子。
     *
     * @param posts 帖子结果列表
     * @param postNo 帖子业务号
     * @return 帖子结果
     */
    private PostResult findPostResult(List<PostResult> posts, Long postNo) {
        return posts.stream()
                .filter(post -> post.postNo().equals(postNo))
                .findFirst()
                .orElseThrow();
    }
}

package site.jianjiange.postservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import site.jianjiange.postservice.cache.LikeCountCache;
import site.jianjiange.postservice.constant.DatabaseSentinel;
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
import site.jianjiange.postservice.manager.PostManager;
import site.jianjiange.postservice.service.command.CreatePostCommand;
import site.jianjiange.postservice.service.result.CreatePostResult;
import site.jianjiange.postservice.service.result.PostResult;
import site.jianjiange.postservice.storage.ObjectMetadata;
import site.jianjiange.postservice.storage.ObjectStorageClient;
import site.jianjiange.postservice.storage.ObjectStorageObjectNotFoundException;

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

    @SpyBean
    private PostManager postManager;

    @MockBean
    private ObjectStorageClient objectStorageClient;

    @MockBean
    private LikeCountCache likeCountCache;

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
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new ObjectMetadata("image/jpeg", 1024L, "etag-test");
        }).when(objectStorageClient).statObject(any(), any());
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
                .eq(PostImageEntity::getPostNo, post.getPostNo())
                .orderByAsc(PostImageEntity::getSortOrder)))
                .extracting(PostImageEntity::getImageNo)
                .containsExactly(7001L, 7002L);
        assertThat(idempotentRequestMapper.selectList(new LambdaQueryWrapper<IdempotentRequestEntity>()
                .eq(IdempotentRequestEntity::getUserId, 9001L)
                .eq(IdempotentRequestEntity::getClientRequestId, "req-create-1")))
                .extracting(IdempotentRequestEntity::getBizNo)
                .containsExactly(result.postNo());
        assertThat(postImageMapper.selectList(new LambdaQueryWrapper<PostImageEntity>()
                .eq(PostImageEntity::getPostNo, post.getPostNo())))
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
     * 验证并发重复创建在幂等记录唯一约束冲突后，会恢复为幂等成功。
     */
    @Test
    void createPostRecoversDuplicateRequestAfterIdempotentInsertConflict() {
        CreatePostCommand command = new CreatePostCommand(9001L, "hello post", List.of(), "req-create-race-1");
        CreatePostResult first = postService.createPost(command);
        doReturn(null)
                .doCallRealMethod()
                .when(postManager)
                .findIdempotentRequest(9001L, "CREATE_POST", "req-create-race-1");

        CreatePostResult second = postService.createPost(command);

        assertThat(second.postNo()).isEqualTo(first.postNo());
        assertThat(second.duplicated()).isTrue();
        assertThat(postMapper.selectCount(new QueryWrapper<>())).isEqualTo(1);
    }

    /**
     * 验证并发重复创建在图片已被第一次请求绑定后，会恢复为幂等成功。
     */
    @Test
    void createPostRecoversDuplicateRequestAfterImageAlreadyBound() {
        insertTempImage(7901L, 9001L);
        CreatePostCommand command = new CreatePostCommand(
                9001L, "hello post", List.of(7901L), "req-create-race-image-1");
        CreatePostResult first = postService.createPost(command);
        doReturn(null)
                .doCallRealMethod()
                .when(postManager)
                .findIdempotentRequest(9001L, "CREATE_POST", "req-create-race-image-1");

        CreatePostResult second = postService.createPost(command);

        assertThat(second.postNo()).isEqualTo(first.postNo());
        assertThat(second.duplicated()).isTrue();
        assertThat(postMapper.selectCount(new QueryWrapper<>())).isEqualTo(1);
        assertThat(postImageMapper.selectList(new LambdaQueryWrapper<PostImageEntity>()
                .eq(PostImageEntity::getImageNo, 7901L)))
                .extracting(PostImageEntity::getStatus)
                .containsExactly(ImageStatus.BOUND);
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
     * 验证发帖不能绑定其他用户的 TEMP 图片。
     */
    @Test
    void createPostRejectsImageOwnedByOtherUser() {
        insertTempImage(7401L, 9002L);

        assertThatThrownBy(() -> postService.createPost(
                new CreatePostCommand(9001L, "hello post", List.of(7401L), "req-image-owner-1")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.IMAGE_FORBIDDEN));

        assertThat(postMapper.selectCount(new QueryWrapper<>())).isZero();
    }

    /**
     * 验证发帖不能绑定已经不是 TEMP 状态的图片。
     */
    @Test
    void createPostRejectsNonTempImage() {
        insertTempImage(7501L, 9001L);
        PostImageEntity image = selectImageByNo(7501L);
        image.setStatus(ImageStatus.BOUND);
        image.setPostNo(12345L);
        postImageMapper.updateById(image);

        assertThatThrownBy(() -> postService.createPost(
                new CreatePostCommand(9001L, "hello post", List.of(7501L), "req-image-status-1")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.IMAGE_STATUS_INVALID));

        assertThat(postMapper.selectCount(new QueryWrapper<>())).isZero();
    }

    /**
     * 验证对象存储中不存在的图片不能被绑定到帖子。
     */
    @Test
    void createPostRejectsImageMissingInObjectStorage() {
        insertTempImage(7601L, 9001L);
        doThrow(new ObjectStorageObjectNotFoundException("missing", null))
                .when(objectStorageClient)
                .statObject(eq("wangjun-dating"), eq("wangjun-tmp/post/9001/7601.jpg"));

        assertThatThrownBy(() -> postService.createPost(
                new CreatePostCommand(9001L, "hello post", List.of(7601L), "req-image-upload-1")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.IMAGE_NOT_UPLOADED));

        assertThat(postMapper.selectCount(new QueryWrapper<>())).isZero();
        assertThat(idempotentRequestMapper.selectCount(new QueryWrapper<>())).isZero();
    }

    /**
     * 验证对象存储中的非法图片类型不能被绑定到帖子。
     */
    @Test
    void createPostRejectsInvalidObjectContentType() {
        insertTempImage(7701L, 9001L);
        doReturn(new ObjectMetadata("text/plain", 1024L, "etag-plain"))
                .when(objectStorageClient)
                .statObject(eq("wangjun-dating"), eq("wangjun-tmp/post/9001/7701.jpg"));

        assertThatThrownBy(() -> postService.createPost(
                new CreatePostCommand(9001L, "hello post", List.of(7701L), "req-image-type-1")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.IMAGE_CONTENT_TYPE_INVALID));

        assertThat(postMapper.selectCount(new QueryWrapper<>())).isZero();
    }

    /**
     * 验证对象存储中的图片大小与 TEMP 记录不一致时不能绑定。
     */
    @Test
    void createPostRejectsMismatchedObjectSize() {
        insertTempImage(7801L, 9001L);
        doReturn(new ObjectMetadata("image/jpeg", 2048L, "etag-size"))
                .when(objectStorageClient)
                .statObject(eq("wangjun-dating"), eq("wangjun-tmp/post/9001/7801.jpg"));

        assertThatThrownBy(() -> postService.createPost(
                new CreatePostCommand(9001L, "hello post", List.of(7801L), "req-image-size-1")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.IMAGE_NOT_UPLOADED));

        assertThat(postMapper.selectCount(new QueryWrapper<>())).isZero();
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
     * 验证帖子详情会叠加 Redis 中尚未回写的点赞 delta。
     */
    @Test
    void getPostDetailAddsPendingRedisLikeDelta() {
        CreatePostResult result = postService.createPost(
                new CreatePostCommand(9001L, "hello post", List.of(), "req-like-delta-detail-1"));
        PostEntity post = selectByPostNo(result.postNo());
        post.setLikeCount(4L);
        postMapper.updateById(post);
        doReturn(6L).when(likeCountCache).readLikeDelta(result.postNo());

        PostResult detail = postService.getPostDetail(result.postNo()).orElseThrow();

        assertThat(detail.likeCount()).isEqualTo(10L);
    }

    /**
     * 验证作者帖子列表批量叠加 Redis 中尚未回写的点赞 delta。
     */
    @Test
    void listAuthorPostsAddsPendingRedisLikeDeltasInBatch() {
        CreatePostResult first = postService.createPost(
                new CreatePostCommand(9001L, "first post", List.of(), "req-like-delta-list-1"));
        CreatePostResult second = postService.createPost(
                new CreatePostCommand(9001L, "second post", List.of(), "req-like-delta-list-2"));
        PostEntity firstPost = selectByPostNo(first.postNo());
        firstPost.setLikeCount(1L);
        postMapper.updateById(firstPost);
        PostEntity secondPost = selectByPostNo(second.postNo());
        secondPost.setLikeCount(2L);
        postMapper.updateById(secondPost);
        doReturn(Map.of(first.postNo(), 3L, second.postNo(), 5L))
                .when(likeCountCache)
                .readLikeDeltas(any());

        List<PostResult> posts = postService.listAuthorPosts(9001L, 20);

        assertThat(findPostResult(posts, first.postNo()).likeCount()).isEqualTo(4L);
        assertThat(findPostResult(posts, second.postNo()).likeCount()).isEqualTo(7L);
        verify(likeCountCache, times(1)).readLikeDeltas(any());
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
        image.setPostNo(DatabaseSentinel.NONE_ID);
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
        image.setBoundAt(DatabaseSentinel.NONE_TIME);
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
     * 按图片业务号查询图片实体。
     *
     * @param imageNo 图片业务号
     * @return 图片实体
     */
    private PostImageEntity selectImageByNo(Long imageNo) {
        return postImageMapper.selectOne(new LambdaQueryWrapper<PostImageEntity>()
                .eq(PostImageEntity::getImageNo, imageNo));
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

package site.jianjiange.postservice.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import site.jianjiange.postservice.entity.CommentEntity;
import site.jianjiange.postservice.entity.IdempotentRequestEntity;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.entity.PostImageEntity;
import site.jianjiange.postservice.entity.PostLikeEntity;
import site.jianjiange.postservice.enums.CommentStatus;
import site.jianjiange.postservice.enums.ImageStatus;
import site.jianjiange.postservice.enums.PostStatus;

/**
 * Mapper 单表 CRUD 测试，覆盖阶段 2 的表结构、实体映射和唯一约束。
 */
@ActiveProfiles("test")
@SpringBootTest
class MapperCrudTest {

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private PostImageMapper postImageMapper;

    @Autowired
    private PostLikeMapper postLikeMapper;

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private IdempotentRequestMapper idempotentRequestMapper;

    /**
     * 验证帖子 Mapper 支持插入、按 ID 查询和条件查询。
     */
    @Test
    void postMapperSupportsSingleTableCrud() {
        PostEntity post = post(1001L, 2001L);

        assertThat(postMapper.insert(post)).isEqualTo(1);
        assertThat(postMapper.selectById(1001L)).isNotNull();
        assertThat(postMapper.selectList(new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getAuthorId, 2001L)
                .eq(PostEntity::getStatus, PostStatus.PUBLISHED)))
                .extracting(PostEntity::getId)
                .containsExactly(1001L);
    }

    /**
     * 验证帖子图片 Mapper 支持插入、按 ID 查询和条件查询。
     */
    @Test
    void postImageMapperSupportsSingleTableCrud() {
        OffsetDateTime now = OffsetDateTime.now();
        PostImageEntity image = new PostImageEntity();
        image.setId(2001L);
        image.setUserId(3001L);
        image.setBucket("wangjun-dating");
        image.setObjectKey("wangjun-tmp/post/3001/202606/image.jpg");
        image.setContentType("image/jpeg");
        image.setSizeBytes(1024L);
        image.setEtag("etag-1");
        image.setWidth(640);
        image.setHeight(480);
        image.setSortOrder(0);
        image.setStatus(ImageStatus.TEMP);
        image.setUploadExpireAt(now.plusHours(1));
        image.setRetryCount(0);
        image.setCreatedAt(now);
        image.setUpdatedAt(now);

        assertThat(postImageMapper.insert(image)).isEqualTo(1);
        assertThat(postImageMapper.selectById(2001L)).isNotNull();
        assertThat(postImageMapper.selectList(new LambdaQueryWrapper<PostImageEntity>()
                .eq(PostImageEntity::getUserId, 3001L)
                .eq(PostImageEntity::getStatus, ImageStatus.TEMP)))
                .extracting(PostImageEntity::getObjectKey)
                .containsExactly("wangjun-tmp/post/3001/202606/image.jpg");
    }

    /**
     * 验证点赞 Mapper 支持单表 CRUD，并验证 user_id 和 post_id 的唯一约束。
     */
    @Test
    void postLikeMapperSupportsSingleTableCrudAndUniqueUserPost() {
        PostLikeEntity like = postLike(3001L, 4001L, 5001L, 5002L);

        assertThat(postLikeMapper.insert(like)).isEqualTo(1);
        assertThat(postLikeMapper.selectById(3001L)).isNotNull();
        assertThat(postLikeMapper.selectList(new LambdaQueryWrapper<PostLikeEntity>()
                .eq(PostLikeEntity::getUserId, 4001L)
                .eq(PostLikeEntity::getPostAuthorId, 5002L)))
                .extracting(PostLikeEntity::getPostId)
                .containsExactly(5001L);

        assertThatThrownBy(() -> postLikeMapper.insert(postLike(3002L, 4001L, 5001L, 5002L)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    /**
     * 验证评论 Mapper 支持插入、按 ID 查询和条件查询。
     */
    @Test
    void commentMapperSupportsSingleTableCrud() {
        OffsetDateTime now = OffsetDateTime.now();
        CommentEntity comment = new CommentEntity();
        comment.setId(4001L);
        comment.setPostId(5001L);
        comment.setAuthorId(6001L);
        comment.setRootCommentId(4001L);
        comment.setContent("hello");
        comment.setLevel(1);
        comment.setStatus(CommentStatus.NORMAL);
        comment.setCreatedAt(now);

        assertThat(commentMapper.insert(comment)).isEqualTo(1);
        assertThat(commentMapper.selectById(4001L)).isNotNull();
        assertThat(commentMapper.selectList(new LambdaQueryWrapper<CommentEntity>()
                .eq(CommentEntity::getPostId, 5001L)
                .eq(CommentEntity::getStatus, CommentStatus.NORMAL)))
                .extracting(CommentEntity::getId)
                .containsExactly(4001L);
    }

    /**
     * 验证幂等请求 Mapper 支持单表 CRUD，并验证用户、操作类型和请求 ID 的唯一约束。
     */
    @Test
    void idempotentRequestMapperSupportsSingleTableCrudAndUniqueRequest() {
        IdempotentRequestEntity request = idempotentRequest(5001L, 6001L, "CREATE_POST", "req-1");

        assertThat(idempotentRequestMapper.insert(request)).isEqualTo(1);
        assertThat(idempotentRequestMapper.selectById(5001L)).isNotNull();
        assertThat(idempotentRequestMapper.selectList(new LambdaQueryWrapper<IdempotentRequestEntity>()
                .eq(IdempotentRequestEntity::getUserId, 6001L)
                .eq(IdempotentRequestEntity::getOperationType, "CREATE_POST")))
                .extracting(IdempotentRequestEntity::getClientRequestId)
                .containsExactly("req-1");

        assertThatThrownBy(() -> idempotentRequestMapper.insert(
                idempotentRequest(5002L, 6001L, "CREATE_POST", "req-1")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    /**
     * 构造用于测试的帖子实体。
     *
     * @param id 帖子 ID
     * @param authorId 作者 ID
     * @return 帖子实体
     */
    private static PostEntity post(Long id, Long authorId) {
        OffsetDateTime now = OffsetDateTime.now();
        PostEntity post = new PostEntity();
        post.setId(id);
        post.setAuthorId(authorId);
        post.setContent("hello post");
        post.setImageCount(0);
        post.setStatus(PostStatus.PUBLISHED);
        post.setLikeCount(0L);
        post.setCommentCount(0L);
        post.setPublishedAt(now);
        post.setVersion(0);
        post.setCreatedAt(now);
        post.setUpdatedAt(now);
        return post;
    }

    /**
     * 构造用于测试的点赞实体。
     *
     * @param id 点赞记录 ID
     * @param userId 点赞用户 ID
     * @param postId 被点赞帖子 ID
     * @param postAuthorId 被点赞帖子作者 ID
     * @return 点赞实体
     */
    private static PostLikeEntity postLike(Long id, Long userId, Long postId, Long postAuthorId) {
        PostLikeEntity like = new PostLikeEntity();
        like.setId(id);
        like.setUserId(userId);
        like.setPostId(postId);
        like.setPostAuthorId(postAuthorId);
        like.setCreatedAt(OffsetDateTime.now());
        return like;
    }

    /**
     * 构造用于测试的幂等请求实体。
     *
     * @param id 幂等记录 ID
     * @param userId 请求用户 ID
     * @param operationType 操作类型
     * @param clientRequestId 客户端请求 ID
     * @return 幂等请求实体
     */
    private static IdempotentRequestEntity idempotentRequest(
            Long id, Long userId, String operationType, String clientRequestId) {
        OffsetDateTime now = OffsetDateTime.now();
        IdempotentRequestEntity request = new IdempotentRequestEntity();
        request.setId(id);
        request.setUserId(userId);
        request.setOperationType(operationType);
        request.setClientRequestId(clientRequestId);
        request.setRequestHash("hash-" + clientRequestId);
        request.setBizId(10001L);
        request.setResponseSnapshot("{\"id\":10001}");
        request.setCreatedAt(now);
        request.setExpireAt(now.plusDays(1));
        return request;
    }
}

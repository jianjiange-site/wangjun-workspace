package site.jianjiange.postservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import site.jianjiange.postservice.constant.DatabaseSentinel;
import site.jianjiange.postservice.entity.CommentEntity;
import site.jianjiange.postservice.entity.IdempotentRequestEntity;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.enums.CommentStatus;
import site.jianjiange.postservice.enums.PostStatus;
import site.jianjiange.postservice.exception.BusinessException;
import site.jianjiange.postservice.exception.PostErrorCode;
import site.jianjiange.postservice.mapper.CommentMapper;
import site.jianjiange.postservice.mapper.IdempotentRequestMapper;
import site.jianjiange.postservice.mapper.PostMapper;
import site.jianjiange.postservice.service.command.CreateCommentCommand;
import site.jianjiange.postservice.service.command.DeleteCommentCommand;
import site.jianjiange.postservice.service.result.CommentPageResult;
import site.jianjiange.postservice.service.result.CommentResult;
import site.jianjiange.postservice.service.result.CreateCommentResult;

/**
 * 评论业务服务测试，覆盖阶段 4.4 的两级评论、删除和幂等规则。
 */
@ActiveProfiles("test")
@SpringBootTest
class CommentServiceTest {

    @Autowired
    private CommentService commentService;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private IdempotentRequestMapper idempotentRequestMapper;

    /**
     * 清理帖子、评论和幂等记录，保证每个用例独立。
     */
    @BeforeEach
    void cleanDatabase() {
        idempotentRequestMapper.delete(new QueryWrapper<>());
        commentMapper.delete(new QueryWrapper<>());
        postMapper.delete(new QueryWrapper<>());
    }

    /**
     * 验证创建一级评论时 root_comment_id 指向自身，并同步增加帖子评论数。
     */
    @Test
    void createCommentCreatesRootCommentAndIncreasesCommentCount() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);

        CreateCommentResult result = commentService.createComment(
                new CreateCommentCommand(
                        3001L, 91001L, DatabaseSentinel.NONE_ID, " hello comment ", "req-comment-1"));

        CommentEntity comment = selectByCommentNo(result.commentNo());
        assertThat(result.duplicated()).isFalse();
        assertThat(comment.getContent()).isEqualTo("hello comment");
        assertThat(comment.getLevel()).isEqualTo(1);
        assertThat(comment.getRootCommentId()).isEqualTo(comment.getId());
        assertThat(comment.getParentCommentId()).isEqualTo(DatabaseSentinel.NONE_ID);
        assertThat(comment.getReplyToUserId()).isEqualTo(DatabaseSentinel.NONE_ID);
        assertThat(postMapper.selectById(1001L).getCommentCount()).isEqualTo(1L);
        assertThat(idempotentRequestMapper.selectList(new LambdaQueryWrapper<IdempotentRequestEntity>()
                .eq(IdempotentRequestEntity::getUserId, 3001L)
                .eq(IdempotentRequestEntity::getClientRequestId, "req-comment-1")))
                .extracting(IdempotentRequestEntity::getBizNo)
                .containsExactly(result.commentNo());
    }

    /**
     * 验证回复一级评论和回复二级评论都展示为二级，并保留被回复用户。
     */
    @Test
    void createCommentKeepsRepliesAtSecondLevel() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);
        Long rootNo = commentService.createComment(
                new CreateCommentCommand(3001L, 91001L, DatabaseSentinel.NONE_ID, "root", "req-root")).commentNo();
        Long firstReplyNo = commentService.createComment(
                new CreateCommentCommand(3002L, 91001L, rootNo, "reply root", "req-reply-1")).commentNo();
        Long secondReplyNo = commentService.createComment(
                new CreateCommentCommand(3003L, 91001L, firstReplyNo, "reply reply", "req-reply-2")).commentNo();

        CommentEntity root = selectByCommentNo(rootNo);
        CommentEntity firstReply = selectByCommentNo(firstReplyNo);
        CommentEntity secondReply = selectByCommentNo(secondReplyNo);

        assertThat(firstReply.getLevel()).isEqualTo(2);
        assertThat(firstReply.getRootCommentId()).isEqualTo(root.getId());
        assertThat(firstReply.getParentCommentId()).isEqualTo(root.getId());
        assertThat(firstReply.getReplyToUserId()).isEqualTo(3001L);
        assertThat(secondReply.getLevel()).isEqualTo(2);
        assertThat(secondReply.getRootCommentId()).isEqualTo(root.getId());
        assertThat(secondReply.getParentCommentId()).isEqualTo(firstReply.getId());
        assertThat(secondReply.getReplyToUserId()).isEqualTo(3002L);
        assertThat(postMapper.selectById(1001L).getCommentCount()).isEqualTo(3L);

        CommentPageResult rootsPage = commentService.listPostComments(91001L, 1);
        List<CommentResult> roots = rootsPage.comments();
        assertThat(roots).hasSize(1);
        assertThat(rootsPage.pageSize()).isEqualTo(50);
        assertThat(roots.get(0).commentNo()).isEqualTo(rootNo);
        assertThat(roots.get(0).parentCommentNo()).isEqualTo(DatabaseSentinel.NONE_ID);
        assertThat(roots.get(0).replyToUserId()).isEqualTo(DatabaseSentinel.NONE_ID);
        assertThat(roots.get(0).replies()).isEmpty();
        assertThat(roots.get(0).replyTotalCount()).isEqualTo(2L);
        assertThat(roots.get(0).replyPageSize()).isEqualTo(10);
        assertThat(roots.get(0).replyTotalPages()).isEqualTo(1);

        CommentPageResult replies = commentService.listCommentRepliesPage(rootNo, 1);
        assertThat(replies.comments())
                .extracting(CommentResult::commentNo)
                .containsExactly(firstReplyNo, secondReplyNo);
        assertThat(replies.comments())
                .extracting(CommentResult::parentCommentNo)
                .containsExactly(rootNo, firstReplyNo);
    }

    /**
     * 验证一级评论和回复都按创建先后稳定返回。
     */
    @Test
    void listCommentsKeepsCreationOrder() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);
        Long firstRootNo = commentService.createComment(
                new CreateCommentCommand(3001L, 91001L, DatabaseSentinel.NONE_ID, "root-1", "req-order-root-1"))
                .commentNo();
        Long secondRootNo = commentService.createComment(
                new CreateCommentCommand(3002L, 91001L, DatabaseSentinel.NONE_ID, "root-2", "req-order-root-2"))
                .commentNo();
        Long firstReplyNo = commentService.createComment(
                new CreateCommentCommand(3003L, 91001L, firstRootNo, "reply-1", "req-order-reply-1"))
                .commentNo();
        Long secondReplyNo = commentService.createComment(
                new CreateCommentCommand(3004L, 91001L, firstRootNo, "reply-2", "req-order-reply-2"))
                .commentNo();

        assertThat(commentService.listPostComments(91001L, 1).comments())
                .extracting(CommentResult::commentNo)
                .containsExactly(firstRootNo, secondRootNo);
        assertThat(commentService.listPostComments(91001L, 1).comments().get(0).replies()).isEmpty();
        assertThat(commentService.listCommentRepliesPage(firstRootNo, 1).comments())
                .extracting(CommentResult::commentNo)
                .containsExactly(firstReplyNo, secondReplyNo);
    }

    /**
     * 验证展开二级回复时可以根据页号跳转，每页固定 10 条。
     */
    @Test
    void listCommentRepliesUsesPageNo() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);
        Long rootNo = commentService.createComment(
                new CreateCommentCommand(3001L, 91001L, DatabaseSentinel.NONE_ID, "root", "req-page-root"))
                .commentNo();
        List<Long> replyNos = createReplies(rootNo, 12, 3100L, "req-page-reply-");

        CommentPageResult firstPage = commentService.listCommentRepliesPage(rootNo, 1);
        CommentPageResult secondPage = commentService.listCommentRepliesPage(rootNo, 2);
        CommentPageResult emptyPage = commentService.listCommentRepliesPage(rootNo, 3);

        assertThat(firstPage.comments())
                .extracting(CommentResult::commentNo)
                .containsExactlyElementsOf(replyNos.subList(0, 10));
        assertThat(firstPage.pageNo()).isEqualTo(1);
        assertThat(firstPage.pageSize()).isEqualTo(10);
        assertThat(firstPage.totalCount()).isEqualTo(12L);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.hasPrevious()).isFalse();
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(secondPage.comments())
                .extracting(CommentResult::commentNo)
                .containsExactlyElementsOf(replyNos.subList(10, 12));
        assertThat(secondPage.hasPrevious()).isTrue();
        assertThat(secondPage.hasNext()).isFalse();
        assertThat(emptyPage.comments()).isEmpty();
        assertThat(emptyPage.pageNo()).isEqualTo(3);
        assertThat(emptyPage.totalPages()).isEqualTo(2);
    }

    /**
     * 验证二级回复页号必须为正整数。
     */
    @Test
    void listCommentRepliesRejectsInvalidPageNo() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);
        Long rootNo = commentService.createComment(
                new CreateCommentCommand(3001L, 91001L, DatabaseSentinel.NONE_ID, "root", "req-page-invalid-root"))
                .commentNo();

        assertThatThrownBy(() -> commentService.listCommentRepliesPage(rootNo, 0))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.INVALID_ARGUMENT));
    }

    /**
     * 验证一级评论列表不返回二级回复预览，但返回二级回复分页需要的总数。
     */
    @Test
    void listPostCommentsReturnsReplyCountWithoutReplyPreview() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);
        Long firstRootNo = commentService.createComment(
                new CreateCommentCommand(3001L, 91001L, DatabaseSentinel.NONE_ID, "root-1", "req-preview-root-1"))
                .commentNo();
        Long secondRootNo = commentService.createComment(
                new CreateCommentCommand(3002L, 91001L, DatabaseSentinel.NONE_ID, "root-2", "req-preview-root-2"))
                .commentNo();
        createReplies(firstRootNo, 51, 3100L, "req-preview-reply-1-");
        createReplies(secondRootNo, 51, 3200L, "req-preview-reply-2-");

        List<CommentResult> roots = commentService.listPostComments(91001L, 1).comments();

        assertThat(roots)
                .extracting(CommentResult::commentNo)
                .containsExactly(firstRootNo, secondRootNo);
        assertThat(roots.get(0).replies()).isEmpty();
        assertThat(roots.get(0).replyTotalCount()).isEqualTo(51L);
        assertThat(roots.get(0).replyPageSize()).isEqualTo(10);
        assertThat(roots.get(0).replyTotalPages()).isEqualTo(6);
        assertThat(roots.get(1).replies()).isEmpty();
        assertThat(roots.get(1).replyTotalCount()).isEqualTo(51L);
        assertThat(roots.get(1).replyPageSize()).isEqualTo(10);
        assertThat(roots.get(1).replyTotalPages()).isEqualTo(6);
    }

    /**
     * 验证一级评论列表按页号分页，每页固定 50 条。
     */
    @Test
    void listPostCommentsUsesPageNo() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);
        List<Long> rootNos = createRootComments(52, 3000L, "req-root-page-");

        CommentPageResult firstPage = commentService.listPostComments(91001L, 1);
        CommentPageResult secondPage = commentService.listPostComments(91001L, 2);
        CommentPageResult emptyPage = commentService.listPostComments(91001L, 3);

        assertThat(firstPage.comments())
                .extracting(CommentResult::commentNo)
                .containsExactlyElementsOf(rootNos.subList(0, 50));
        assertThat(firstPage.pageSize()).isEqualTo(50);
        assertThat(firstPage.totalCount()).isEqualTo(52L);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.hasPrevious()).isFalse();
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(secondPage.comments())
                .extracting(CommentResult::commentNo)
                .containsExactlyElementsOf(rootNos.subList(50, 52));
        assertThat(secondPage.hasPrevious()).isTrue();
        assertThat(secondPage.hasNext()).isFalse();
        assertThat(emptyPage.comments()).isEmpty();
        assertThat(emptyPage.pageNo()).isEqualTo(3);
        assertThat(emptyPage.totalPages()).isEqualTo(2);
    }

    /**
     * 验证删除一级评论不会级联删除回复，且评论数按未删除评论数递减。
     */
    @Test
    void deleteCommentDoesNotCascadeReplies() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);
        Long rootNo = commentService.createComment(
                new CreateCommentCommand(
                        3001L, 91001L, DatabaseSentinel.NONE_ID, "root", "req-delete-root")).commentNo();
        Long replyNo = commentService.createComment(
                new CreateCommentCommand(3002L, 91001L, rootNo, "reply", "req-delete-reply")).commentNo();

        commentService.deleteComment(new DeleteCommentCommand(3001L, rootNo));

        assertThat(selectByCommentNo(rootNo).getStatus()).isEqualTo(CommentStatus.USER_DELETED);
        assertThat(selectByCommentNo(replyNo).getStatus()).isEqualTo(CommentStatus.NORMAL);
        assertThat(postMapper.selectById(1001L).getCommentCount()).isEqualTo(1L);
        assertThat(commentService.listPostComments(91001L, 1).comments()).isEmpty();
        assertThat(commentService.listCommentRepliesPage(rootNo, 1).comments())
                .extracting(CommentResult::commentNo)
                .containsExactly(replyNo);
    }

    /**
     * 验证非评论作者不能删除评论。
     */
    @Test
    void deleteCommentRejectsNonAuthor() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);
        Long commentNo = commentService.createComment(
                new CreateCommentCommand(
                        3001L, 91001L, DatabaseSentinel.NONE_ID, "root", "req-forbidden-delete")).commentNo();

        assertThatThrownBy(() -> commentService.deleteComment(new DeleteCommentCommand(3002L, commentNo)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.COMMENT_FORBIDDEN));

        assertThat(selectByCommentNo(commentNo).getStatus()).isEqualTo(CommentStatus.NORMAL);
        assertThat(postMapper.selectById(1001L).getCommentCount()).isEqualTo(1L);
    }

    /**
     * 验证重复创建同一个幂等请求时返回原评论业务号，不会重复增加评论数。
     */
    @Test
    void createCommentReturnsExistingResultForSameIdempotentRequest() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);
        CreateCommentCommand command = new CreateCommentCommand(
                3001L, 91001L, DatabaseSentinel.NONE_ID, "same comment", "req-comment-dup");

        CreateCommentResult first = commentService.createComment(command);
        CreateCommentResult second = commentService.createComment(command);

        assertThat(second.commentNo()).isEqualTo(first.commentNo());
        assertThat(second.duplicated()).isTrue();
        assertThat(commentMapper.selectCount(new QueryWrapper<>())).isEqualTo(1L);
        assertThat(postMapper.selectById(1001L).getCommentCount()).isEqualTo(1L);
    }

    /**
     * 验证同一幂等请求号对应不同评论内容时拒绝处理。
     */
    @Test
    void createCommentRejectsChangedPayloadForSameIdempotentRequest() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);
        commentService.createComment(new CreateCommentCommand(
                3001L, 91001L, DatabaseSentinel.NONE_ID, "first", "req-comment-conflict"));

        assertThatThrownBy(() -> commentService.createComment(new CreateCommentCommand(
                3001L, 91001L, DatabaseSentinel.NONE_ID, "changed", "req-comment-conflict")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.IDEMPOTENT_CONFLICT));
    }

    /**
     * 验证不可见帖子不能评论。
     */
    @Test
    void createCommentRejectsUnpublishedPost() {
        insertPost(1001L, 91001L, 2001L, PostStatus.USER_DELETED);

        assertThatThrownBy(() -> commentService.createComment(new CreateCommentCommand(
                3001L, 91001L, DatabaseSentinel.NONE_ID, "hidden", "req-hidden-post")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.POST_NOT_PUBLISHED));

        assertThat(commentMapper.selectCount(new QueryWrapper<>())).isZero();
        assertThat(postMapper.selectById(1001L).getCommentCount()).isZero();
    }

    /**
     * 验证查询不可见帖子的评论列表时抛出帖子不存在。
     */
    @Test
    void listPostCommentsRejectsUnpublishedPost() {
        insertPost(1001L, 91001L, 2001L, PostStatus.USER_DELETED);

        assertThatThrownBy(() -> commentService.listPostComments(91001L, 1))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.POST_NOT_FOUND));
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

    private CommentEntity selectByCommentNo(Long commentNo) {
        return commentMapper.selectOne(new LambdaQueryWrapper<CommentEntity>()
                .eq(CommentEntity::getCommentNo, commentNo));
    }

    private List<Long> createRootComments(int count, long firstAuthorId, String requestPrefix) {
        List<Long> rootNos = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            rootNos.add(commentService.createComment(new CreateCommentCommand(
                    firstAuthorId + index,
                    91001L,
                    DatabaseSentinel.NONE_ID,
                    "root-" + index,
                    requestPrefix + index)).commentNo());
        }
        return rootNos;
    }

    private List<Long> createReplies(Long rootNo, int count, long firstAuthorId, String requestPrefix) {
        List<Long> replyNos = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            replyNos.add(commentService.createComment(new CreateCommentCommand(
                    firstAuthorId + index,
                    91001L,
                    rootNo,
                    "reply-" + index,
                    requestPrefix + index)).commentNo());
        }
        return replyNos;
    }
}

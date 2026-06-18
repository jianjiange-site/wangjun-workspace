package site.jianjiange.postservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import site.jianjiange.postservice.cache.CommentCache;
import site.jianjiange.postservice.cache.PendingCommentAcceptResult;
import site.jianjiange.postservice.cache.PendingCommentRecord;
import site.jianjiange.postservice.constant.DatabaseSentinel;
import site.jianjiange.postservice.entity.CommentEntity;
import site.jianjiange.postservice.entity.IdempotentRequestEntity;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.enums.CommentStatus;
import site.jianjiange.postservice.enums.PostStatus;
import site.jianjiange.postservice.exception.BusinessException;
import site.jianjiange.postservice.exception.PostErrorCode;
import site.jianjiange.postservice.job.CommentFlushJob;
import site.jianjiange.postservice.mapper.CommentMapper;
import site.jianjiange.postservice.mapper.IdempotentRequestMapper;
import site.jianjiange.postservice.mapper.PostMapper;
import site.jianjiange.postservice.service.command.CreateCommentCommand;
import site.jianjiange.postservice.service.command.DeleteCommentCommand;
import site.jianjiange.postservice.service.result.CommentPageResult;
import site.jianjiange.postservice.service.result.CommentResult;
import site.jianjiange.postservice.service.result.CreateCommentResult;

/**
 * 评论业务服务测试，覆盖 Redis pending 接收、数据库分页展示、删除和异步回写规则。
 */
@ActiveProfiles("test")
@SpringBootTest
class CommentServiceTest {

    @Autowired
    private CommentService commentService;

    @Autowired
    private CommentFlushJob commentFlushJob;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private IdempotentRequestMapper idempotentRequestMapper;

    @MockBean
    private CommentCache commentCache;

    private final Map<Long, PendingCommentRecord> pendingByNo = new LinkedHashMap<>();
    private final List<Long> pendingCreateQueue = new ArrayList<>();
    private final Set<Long> pendingCreateSet = new LinkedHashSet<>();
    private final List<Long> pendingDeleteQueue = new ArrayList<>();
    private final Set<Long> pendingDeleteSet = new LinkedHashSet<>();
    private final Map<String, IdempotentState> idempotentStates = new HashMap<>();

    /**
     * 清理帖子、评论、幂等记录和模拟 Redis pending，保证每个用例独立。
     */
    @BeforeEach
    void cleanDatabase() {
        idempotentRequestMapper.delete(new QueryWrapper<>());
        commentMapper.delete(new QueryWrapper<>());
        postMapper.delete(new QueryWrapper<>());
        resetFakeCommentCache();
    }

    /**
     * 验证创建一级评论时先写入 Redis pending，列表在 flush 后基于数据库可见。
     */
    @Test
    void createCommentWritesPendingThenFlushesRootComment() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);

        CreateCommentResult result = commentService.createComment(
                new CreateCommentCommand(
                        3001L, 91001L, DatabaseSentinel.NONE_ID, " hello comment ", "req-comment-1"));

        assertThat(result.duplicated()).isFalse();
        assertThat(selectByCommentNo(result.commentNo())).isNull();
        assertThat(postMapper.selectById(1001L).getCommentCount()).isZero();
        assertThat(idempotentRequestMapper.selectCount(new QueryWrapper<IdempotentRequestEntity>())).isZero();
        assertThat(listFirstPostComments(91001L).comments()).isEmpty();

        flushComments();

        CommentEntity comment = selectByCommentNo(result.commentNo());
        assertThat(comment.getContent()).isEqualTo("hello comment");
        assertThat(comment.getLevel()).isEqualTo(1);
        assertThat(comment.getRootCommentNo()).isEqualTo(comment.getCommentNo());
        assertThat(comment.getParentCommentNo()).isEqualTo(DatabaseSentinel.NONE_ID);
        assertThat(comment.getReplyToUserId()).isEqualTo(DatabaseSentinel.NONE_ID);
        assertThat(postMapper.selectById(1001L).getCommentCount()).isEqualTo(1L);
        assertThat(listFirstPostComments(91001L).comments())
                .extracting(CommentResult::content)
                .containsExactly("hello comment");
    }

    /**
     * 验证可回复 pending 一级评论和 pending 二级评论，flush 后都展示为二级并保留被回复用户。
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

        flushComments();

        CommentPageResult rootsPage = listFirstPostComments(91001L);
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

        CommentEntity firstReply = selectByCommentNo(firstReplyNo);
        CommentEntity secondReply = selectByCommentNo(secondReplyNo);
        assertThat(firstReply.getLevel()).isEqualTo(2);
        assertThat(firstReply.getRootCommentNo()).isEqualTo(rootNo);
        assertThat(firstReply.getParentCommentNo()).isEqualTo(rootNo);
        assertThat(firstReply.getReplyToUserId()).isEqualTo(3001L);
        assertThat(secondReply.getLevel()).isEqualTo(2);
        assertThat(secondReply.getRootCommentNo()).isEqualTo(rootNo);
        assertThat(secondReply.getParentCommentNo()).isEqualTo(firstReplyNo);
        assertThat(secondReply.getReplyToUserId()).isEqualTo(3002L);
        assertThat(postMapper.selectById(1001L).getCommentCount()).isEqualTo(3L);
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

        flushComments();

        assertThat(listFirstPostComments(91001L).comments())
                .extracting(CommentResult::commentNo)
                .containsExactly(firstRootNo, secondRootNo);
        assertThat(listFirstPostComments(91001L).comments().get(0).replies()).isEmpty();
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
        flushComments();

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
        flushComments();

        List<CommentResult> roots = listFirstPostComments(91001L).comments();

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
     * 验证一级评论列表按游标不断读取下一批，每批固定 50 条。
     */
    @Test
    void listPostCommentsUsesCursor() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);
        List<Long> rootNos = createRootComments(52, 3000L, "req-root-page-");
        flushComments();

        CommentPageResult firstPage = listFirstPostComments(91001L);
        CommentPageResult secondPage = commentService.listPostComments(
                91001L,
                firstPage.nextCursorCreatedAt(),
                firstPage.nextCursorCommentNo());
        CommentPageResult emptyPage = commentService.listPostComments(
                91001L,
                secondPage.nextCursorCreatedAt(),
                secondPage.nextCursorCommentNo());

        assertThat(firstPage.comments())
                .extracting(CommentResult::commentNo)
                .containsExactlyElementsOf(rootNos.subList(0, 50));
        assertThat(firstPage.pageNo()).isEqualTo(DatabaseSentinel.NONE_NUMBER);
        assertThat(firstPage.pageSize()).isEqualTo(50);
        assertThat(firstPage.totalCount()).isEqualTo(DatabaseSentinel.NONE_ID);
        assertThat(firstPage.totalPages()).isEqualTo(DatabaseSentinel.NONE_NUMBER);
        assertThat(firstPage.hasPrevious()).isFalse();
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.nextCursorCommentNo()).isEqualTo(rootNos.get(49));
        assertThat(secondPage.comments())
                .extracting(CommentResult::commentNo)
                .containsExactlyElementsOf(rootNos.subList(50, 52));
        assertThat(secondPage.hasPrevious()).isFalse();
        assertThat(secondPage.hasNext()).isFalse();
        assertThat(secondPage.nextCursorCommentNo()).isEqualTo(rootNos.get(51));
        assertThat(emptyPage.comments()).isEmpty();
        assertThat(emptyPage.pageNo()).isEqualTo(DatabaseSentinel.NONE_NUMBER);
        assertThat(emptyPage.totalPages()).isEqualTo(DatabaseSentinel.NONE_NUMBER);
        assertThat(emptyPage.nextCursorCommentNo()).isEqualTo(secondPage.nextCursorCommentNo());
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

        flushComments();

        assertThat(selectByCommentNo(rootNo).getStatus()).isEqualTo(CommentStatus.USER_DELETED);
        assertThat(selectByCommentNo(replyNo).getStatus()).isEqualTo(CommentStatus.NORMAL);
        assertThat(postMapper.selectById(1001L).getCommentCount()).isEqualTo(1L);
        assertThat(listFirstPostComments(91001L).comments()).isEmpty();
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

        assertThat(listFirstPostComments(91001L).comments()).isEmpty();
        flushComments();
        assertThat(selectByCommentNo(commentNo).getStatus()).isEqualTo(CommentStatus.NORMAL);
        assertThat(postMapper.selectById(1001L).getCommentCount()).isEqualTo(1L);
        assertThat(listFirstPostComments(91001L).comments())
                .extracting(CommentResult::commentNo)
                .containsExactly(commentNo);
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
        assertThat(commentMapper.selectCount(new QueryWrapper<>())).isZero();
        assertThat(postMapper.selectById(1001L).getCommentCount()).isZero();
        assertThat(listFirstPostComments(91001L).comments()).isEmpty();

        flushComments();

        assertThat(commentMapper.selectCount(new QueryWrapper<>())).isEqualTo(1L);
        assertThat(postMapper.selectById(1001L).getCommentCount()).isEqualTo(1L);
        assertThat(listFirstPostComments(91001L).comments()).hasSize(1);
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
        assertThat(pendingByNo).isEmpty();
        assertThat(postMapper.selectById(1001L).getCommentCount()).isZero();
    }

    /**
     * 验证 Redis 不可用时评论创建失败，不降级写数据库。
     */
    @Test
    void createCommentRejectsWhenRedisUnavailable() {
        insertPost(1001L, 91001L, 2001L, PostStatus.PUBLISHED);
        reset(commentCache);
        doThrow(new RuntimeException("redis down"))
                .when(commentCache)
                .findCreateResult(anyLong(), anyString(), anyString());

        assertThatThrownBy(() -> commentService.createComment(new CreateCommentCommand(
                3001L, 91001L, DatabaseSentinel.NONE_ID, "hidden", "req-redis-down")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.COMMENT_TEMPORARILY_UNAVAILABLE));

        assertThat(commentMapper.selectCount(new QueryWrapper<>())).isZero();
        assertThat(postMapper.selectById(1001L).getCommentCount()).isZero();
    }

    /**
     * 验证查询不可见帖子的评论列表时抛出帖子不存在。
     */
    @Test
    void listPostCommentsRejectsUnpublishedPost() {
        insertPost(1001L, 91001L, 2001L, PostStatus.USER_DELETED);

        assertThatThrownBy(() -> listFirstPostComments(91001L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(PostErrorCode.POST_NOT_FOUND));
    }

    private void resetFakeCommentCache() {
        reset(commentCache);
        pendingByNo.clear();
        pendingCreateQueue.clear();
        pendingCreateSet.clear();
        pendingDeleteQueue.clear();
        pendingDeleteSet.clear();
        idempotentStates.clear();
        stubFakeCommentCache();
    }

    private void stubFakeCommentCache() {
        doAnswer(invocation -> {
            PendingCommentRecord record = invocation.getArgument(0);
            String requestHash = invocation.getArgument(1);
            String clientRequestId = invocation.getArgument(2);
            String idempotentKey = idempotentKey(record.authorId(), clientRequestId);
            IdempotentState existing = idempotentStates.get(idempotentKey);
            if (existing != null) {
                return new PendingCommentAcceptResult(
                        existing.commentNo(), true, !existing.requestHash().equals(requestHash));
            }

            idempotentStates.put(idempotentKey, new IdempotentState(requestHash, record.commentNo()));
            writePendingRecord(record);
            pendingCreateQueue.add(record.commentNo());
            pendingCreateSet.add(record.commentNo());
            return new PendingCommentAcceptResult(record.commentNo(), false, false);
        }).when(commentCache).acceptCreate(any(PendingCommentRecord.class), anyString(), anyString());

        doAnswer(invocation -> {
            Long authorId = invocation.getArgument(0);
            String clientRequestId = invocation.getArgument(1);
            String requestHash = invocation.getArgument(2);
            IdempotentState existing = idempotentStates.get(idempotentKey(authorId, clientRequestId));
            if (existing == null) {
                return Optional.empty();
            }
            return Optional.of(new PendingCommentAcceptResult(
                    existing.commentNo(), true, !existing.requestHash().equals(requestHash)));
        }).when(commentCache).findCreateResult(anyLong(), anyString(), anyString());

        doAnswer(invocation -> Optional.ofNullable(pendingByNo.get(invocation.getArgument(0))))
                .when(commentCache)
                .findPendingComment(anyLong());
        doAnswer(invocation -> pendingDeleteSet.contains(invocation.getArgument(0)))
                .when(commentCache)
                .isPendingDeleted(anyLong());
        doAnswer(invocation -> pendingCreateSet.contains(invocation.getArgument(0)))
                .when(commentCache)
                .hasPendingCreate(anyLong());
        doAnswer(invocation -> distinctList(pendingCreateQueue))
                .when(commentCache)
                .listPendingCreateCommentNos();
        doAnswer(invocation -> distinctList(pendingDeleteQueue))
                .when(commentCache)
                .listPendingDeleteCommentNos();
        doAnswer(invocation -> {
            PendingCommentRecord record = invocation.getArgument(0);
            if (!pendingDeleteSet.add(record.commentNo())) {
                return false;
            }
            writePendingRecord(record);
            pendingDeleteQueue.add(record.commentNo());
            return true;
        }).when(commentCache).acceptDelete(any(PendingCommentRecord.class));
        doAnswer(invocation -> {
            ackCreate(invocation.getArgument(0));
            return null;
        }).when(commentCache).ackCreate(any(PendingCommentRecord.class));
        doAnswer(invocation -> {
            ackDelete(invocation.getArgument(0));
            return null;
        }).when(commentCache).ackDelete(any(PendingCommentRecord.class));
        doAnswer(invocation -> {
            Long commentNo = invocation.getArgument(0);
            pendingCreateQueue.removeIf(commentNo::equals);
            pendingCreateSet.remove(commentNo);
            return null;
        }).when(commentCache).discardPendingCreate(anyLong());
        doAnswer(invocation -> {
            Long commentNo = invocation.getArgument(0);
            pendingDeleteQueue.removeIf(commentNo::equals);
            pendingDeleteSet.remove(commentNo);
            return null;
        }).when(commentCache).discardPendingDelete(anyLong());
        doAnswer(invocation -> Optional.of("test-comment-flush-lock")).when(commentCache).acquireFlushLock();
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

    private void flushComments() {
        commentFlushJob.flushComments();
    }

    private CommentPageResult listFirstPostComments(Long postNo) {
        return commentService.listPostComments(postNo, DatabaseSentinel.NONE_TIME, DatabaseSentinel.NONE_ID);
    }

    private void writePendingRecord(PendingCommentRecord record) {
        pendingByNo.put(record.commentNo(), record);
    }

    private void deletePendingRecord(PendingCommentRecord record) {
        pendingByNo.remove(record.commentNo());
    }

    private List<Long> distinctList(List<Long> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private void ackCreate(PendingCommentRecord record) {
        pendingCreateQueue.removeIf(record.commentNo()::equals);
        if (record.status() == CommentStatus.NORMAL) {
            pendingCreateSet.remove(record.commentNo());
        } else if (!pendingDeleteSet.contains(record.commentNo())) {
            pendingCreateSet.remove(record.commentNo());
        }
        if (!pendingDeleteSet.contains(record.commentNo())) {
            deletePendingRecord(record);
        }
    }

    private void ackDelete(PendingCommentRecord record) {
        pendingDeleteQueue.removeIf(record.commentNo()::equals);
        pendingDeleteSet.remove(record.commentNo());
        pendingCreateSet.remove(record.commentNo());
        deletePendingRecord(record);
    }

    private String idempotentKey(Long authorId, String clientRequestId) {
        return authorId + ":" + clientRequestId;
    }

    private record IdempotentState(String requestHash, Long commentNo) {
    }
}

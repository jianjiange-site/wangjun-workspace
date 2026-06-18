package site.jianjiange.postservice.service;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.jianjiange.postservice.cache.CommentCache;
import site.jianjiange.postservice.cache.PendingCommentAcceptResult;
import site.jianjiange.postservice.cache.PendingCommentRecord;
import site.jianjiange.postservice.constant.DatabaseSentinel;
import site.jianjiange.postservice.entity.CommentEntity;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.enums.CommentStatus;
import site.jianjiange.postservice.enums.PostStatus;
import site.jianjiange.postservice.exception.BusinessException;
import site.jianjiange.postservice.exception.PostErrorCode;
import site.jianjiange.postservice.manager.CommentManager;
import site.jianjiange.postservice.service.command.CreateCommentCommand;
import site.jianjiange.postservice.service.command.DeleteCommentCommand;
import site.jianjiange.postservice.service.result.CommentPageResult;
import site.jianjiange.postservice.service.result.CommentResult;
import site.jianjiange.postservice.service.result.CreateCommentResult;

/**
 * 评论业务服务，负责接收 Redis pending 评论、删除事实和数据库基准评论列表查询。
 */
@Service
public class CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentService.class);
    private static final int MAX_CONTENT_LENGTH = 1000;
    private static final int ROOT_COMMENT_PAGE_SIZE = 50;
    private static final int REPLY_PAGE_SIZE = 10;

    private final IdentifierGenerator identifierGenerator;
    private final CommentManager commentManager;
    private final CommentCache commentCache;

    /**
     * 创建评论业务服务。
     *
     * @param identifierGenerator 业务号生成器
     * @param commentManager 评论数据管理器
     * @param commentCache 评论 pending 缓存
     */
    public CommentService(
            IdentifierGenerator identifierGenerator,
            CommentManager commentManager,
            CommentCache commentCache) {
        this.identifierGenerator = identifierGenerator;
        this.commentManager = commentManager;
        this.commentCache = commentCache;
    }

    /**
     * 创建一级评论或二级回复；请求路径只写 Redis pending，异步任务稍后回写数据库。
     *
     * @param command 创建评论命令
     * @return 创建评论结果
     */
    public CreateCommentResult createComment(CreateCommentCommand command) {
        ValidCreateCommentCommand validCommand = validateCreateCommentCommand(command);
        String requestHash = hashCreateCommentRequest(validCommand);
        Optional<PendingCommentAcceptResult> existingResult = findPendingCreateResult(
                validCommand.authorId(), validCommand.clientRequestId(), requestHash);
        if (existingResult.isPresent()) {
            PendingCommentAcceptResult existing = existingResult.orElseThrow();
            if (existing.conflict()) {
                throw new BusinessException(PostErrorCode.IDEMPOTENT_CONFLICT, "幂等请求内容不一致");
            }
            return new CreateCommentResult(existing.commentNo(), true);
        }

        PostEntity post = commentManager.findPostByPostNo(validCommand.postNo());
        if (post == null) {
            throw new BusinessException(PostErrorCode.POST_NOT_FOUND, "帖子不存在");
        }
        if (post.getStatus() != PostStatus.PUBLISHED) {
            throw new BusinessException(PostErrorCode.POST_NOT_PUBLISHED, "只能评论公开可见帖子");
        }

        Optional<ParentCommentContext> parentContext = resolveParentComment(
                validCommand.parentCommentNo(), post.getPostNo());
        PendingCommentRecord record = buildPendingCreateRecord(validCommand, post.getPostNo(), parentContext);
        PendingCommentAcceptResult accepted = acceptPendingCreate(
                record, requestHash, validCommand.clientRequestId());
        if (accepted.conflict()) {
            throw new BusinessException(PostErrorCode.IDEMPOTENT_CONFLICT, "幂等请求内容不一致");
        }
        return new CreateCommentResult(accepted.commentNo(), accepted.duplicated());
    }

    /**
     * 删除评论，仅允许评论作者软删除自己的正常评论，不级联删除回复。
     *
     * @param command 删除评论命令
     */
    public void deleteComment(DeleteCommentCommand command) {
        validateDeleteCommentCommand(command);
        ResolvedComment resolved = resolveDeletableComment(command.commentNo());
        CommentEntity comment = resolved.comment();
        if (!command.operatorId().equals(comment.getAuthorId())) {
            throw new BusinessException(PostErrorCode.COMMENT_FORBIDDEN, "只能删除自己的评论");
        }
        PendingCommentRecord record = toDeletedPendingRecord(comment, resolved.persisted(), OffsetDateTime.now());
        acceptPendingDelete(record);
    }

    /**
     * 按游标查询帖子一级评论列表，每页固定 50 条。
     *
     * @param postNo 帖子业务号
     * @param cursorCreatedAt 上一页最后一条评论的创建时间；首次查询传空或哨兵时间
     * @param cursorCommentNo 上一页最后一条评论业务号；首次查询传空或 -1
     * @return 一级评论分页结果
     */
    @Transactional(readOnly = true)
    public CommentPageResult listPostComments(
            Long postNo,
            OffsetDateTime cursorCreatedAt,
            Long cursorCommentNo) {
        validatePositive(postNo, "postNo");
        RootCommentCursor cursor = normalizeRootCommentCursor(cursorCreatedAt, cursorCommentNo);
        PostEntity post = commentManager.findPostByPostNo(postNo);
        if (post == null || post.getStatus() != PostStatus.PUBLISHED) {
            throw new BusinessException(PostErrorCode.POST_NOT_FOUND, "帖子不存在");
        }

        List<CommentEntity> fetchedRoots = commentManager.listNormalRootCommentsAfterCursor(
                postNo,
                cursor.createdAt(),
                cursor.commentNo(),
                ROOT_COMMENT_PAGE_SIZE + 1);
        boolean hasNext = fetchedRoots.size() > ROOT_COMMENT_PAGE_SIZE;
        List<CommentEntity> roots = fetchedRoots.stream()
                .limit(ROOT_COMMENT_PAGE_SIZE)
                .toList();
        Set<Long> rootCommentNos = new HashSet<>();
        roots.forEach(root -> rootCommentNos.add(root.getCommentNo()));
        Map<Long, Long> replyCountsByRootNo = commentManager.countNormalRepliesByRootNos(rootCommentNos);
        List<CommentResult> results = roots.stream()
                .map(root -> toResult(
                        root,
                        List.of(),
                        replyCountsByRootNo.getOrDefault(root.getCommentNo(), 0L)))
                .toList();
        RootCommentCursor nextCursor = nextRootCommentCursor(roots, cursor);
        return new CommentPageResult(
                results,
                DatabaseSentinel.NONE_NUMBER,
                ROOT_COMMENT_PAGE_SIZE,
                DatabaseSentinel.NONE_ID,
                DatabaseSentinel.NONE_NUMBER,
                false,
                hasNext,
                nextCursor.createdAt(),
                nextCursor.commentNo());
    }

    /**
     * 按页号查询某个一级评论下的二级回复列表，每页固定 10 条。
     *
     * @param rootCommentNo 一级评论业务号
     * @param pageNo 页号，从 1 开始
     * @return 二级回复分页结果
     */
    @Transactional(readOnly = true)
    public CommentPageResult listCommentRepliesPage(Long rootCommentNo, int pageNo) {
        validatePositive(pageNo, "pageNo");
        CommentEntity root = resolveRootCommentForReplyPage(rootCommentNo);
        long totalCount = commentManager.countNormalRepliesByRootNo(root.getCommentNo());
        int totalPages = totalPages(totalCount, REPLY_PAGE_SIZE);
        List<CommentEntity> replies = pageNo > totalPages && totalPages > 0
                ? List.of()
                : commentManager.listNormalRepliesByRootNoPage(root.getCommentNo(), pageNo, REPLY_PAGE_SIZE);
        if (replies.isEmpty()) {
            return new CommentPageResult(
                    List.of(),
                    pageNo,
                    REPLY_PAGE_SIZE,
                    totalCount,
                    totalPages,
                    pageNo > 1 && totalPages > 0,
                    false);
        }
        List<CommentResult> results = replies.stream()
                .map(reply -> toResult(reply, List.of(), 0L))
                .toList();
        return new CommentPageResult(
                results,
                pageNo,
                REPLY_PAGE_SIZE,
                totalCount,
                totalPages,
                pageNo > 1 && totalPages > 0,
                pageNo < totalPages);
    }

    /**
     * 构造 Redis pending 创建事实。
     */
    private PendingCommentRecord buildPendingCreateRecord(
            ValidCreateCommentCommand validCommand,
            Long postNo,
            Optional<ParentCommentContext> parentContext) {
        CommentEntity seed = new CommentEntity();
        Long id = nextBusinessNo(seed);
        Long commentNo = nextBusinessNo(seed);
        OffsetDateTime now = OffsetDateTime.now();
        if (parentContext.isEmpty()) {
            return new PendingCommentRecord(
                    id,
                    commentNo,
                    postNo,
                    validCommand.authorId(),
                    commentNo,
                    DatabaseSentinel.NONE_ID,
                    DatabaseSentinel.NONE_ID,
                    validCommand.content(),
                    1,
                    CommentStatus.NORMAL,
                    now,
                    DatabaseSentinel.NONE_TIME,
                    false);
        }

        ParentCommentContext context = parentContext.orElseThrow();
        return new PendingCommentRecord(
                id,
                commentNo,
                postNo,
                validCommand.authorId(),
                context.root().getCommentNo(),
                context.parent().getCommentNo(),
                context.parent().getAuthorId(),
                validCommand.content(),
                2,
                CommentStatus.NORMAL,
                now,
                DatabaseSentinel.NONE_TIME,
                false);
    }

    /**
     * 校验并解析父评论上下文。
     *
     * @param parentCommentNo 父评论业务号
     * @param postNo 当前帖子业务号
     * @return 父评论上下文；创建一级评论时返回空 Optional
     */
    private Optional<ParentCommentContext> resolveParentComment(Long parentCommentNo, Long postNo) {
        if (DatabaseSentinel.isNoneId(parentCommentNo)) {
            return Optional.empty();
        }
        CommentEntity parent = resolveNormalCommentForCreate(parentCommentNo, "父评论不存在");
        if (!postNo.equals(parent.getPostNo())) {
            throw new BusinessException(PostErrorCode.COMMENT_NOT_FOUND, "父评论不存在");
        }
        CommentEntity root = parent.getLevel() == 1
                ? parent
                : resolveNormalCommentForCreate(parent.getRootCommentNo(), "一级评论不存在");
        if (root.getLevel() != 1
                || root.getStatus() != CommentStatus.NORMAL
                || !postNo.equals(root.getPostNo())
                || isPendingDeleted(root.getCommentNo())) {
            throw new BusinessException(PostErrorCode.COMMENT_NOT_FOUND, "一级评论不存在");
        }
        return Optional.of(new ParentCommentContext(parent, root));
    }

    /**
     * 查询可被回复的正常评论，pending 删除中的数据库评论视为不可回复。
     */
    private CommentEntity resolveNormalCommentForCreate(Long commentNo, String notFoundMessage) {
        if (isPendingDeleted(commentNo)) {
            throw new BusinessException(PostErrorCode.COMMENT_NOT_FOUND, notFoundMessage);
        }
        CommentEntity dbComment = commentManager.findByCommentNo(commentNo);
        if (dbComment != null && dbComment.getStatus() == CommentStatus.NORMAL) {
            return dbComment;
        }
        Optional<PendingCommentRecord> pending = findPendingComment(commentNo);
        if (pending.isPresent() && pending.orElseThrow().status() == CommentStatus.NORMAL) {
            return pending.orElseThrow().toEntity();
        }
        throw new BusinessException(PostErrorCode.COMMENT_NOT_FOUND, notFoundMessage);
    }

    /**
     * 查询可删除评论，pending 删除中的评论对用户表现为已删除。
     */
    private ResolvedComment resolveDeletableComment(Long commentNo) {
        boolean pendingDeleted = isPendingDeleted(commentNo);
        CommentEntity dbComment = commentManager.findByCommentNo(commentNo);
        if (dbComment != null && dbComment.getStatus() == CommentStatus.NORMAL && !pendingDeleted) {
            return new ResolvedComment(dbComment, true);
        }
        Optional<PendingCommentRecord> pending = findPendingComment(commentNo);
        if (pending.isPresent() && pending.orElseThrow().status() == CommentStatus.NORMAL && !pendingDeleted) {
            return new ResolvedComment(pending.orElseThrow().toEntity(), false);
        }
        throw new BusinessException(PostErrorCode.COMMENT_NOT_FOUND, "评论不存在");
    }

    /**
     * 校验并解析一级评论；一级评论被删除后仍允许分页查看其已有回复。
     *
     * @param rootCommentNo 一级评论业务号
     * @return 一级评论实体
     */
    private CommentEntity resolveRootCommentForReplyPage(Long rootCommentNo) {
        validatePositive(rootCommentNo, "rootCommentNo");
        CommentEntity root = commentManager.findByCommentNo(rootCommentNo);
        if (root == null || root.getLevel() != 1) {
            throw new BusinessException(PostErrorCode.COMMENT_NOT_FOUND, "一级评论不存在");
        }
        PostEntity post = commentManager.findPostByPostNo(root.getPostNo());
        if (post == null || post.getStatus() != PostStatus.PUBLISHED) {
            throw new BusinessException(PostErrorCode.POST_NOT_FOUND, "帖子不存在");
        }
        return root;
    }

    /**
     * 转换评论实体为业务结果。
     *
     * @param comment 评论实体
     * @param replies 内嵌回复列表，当前一级评论分页不使用
     * @return 评论结果
     */
    private CommentResult toResult(
            CommentEntity comment,
            List<CommentEntity> replies,
            long replyTotalCount) {
        List<CommentResult> replyResults = replies.stream()
                .map(reply -> toResult(reply, List.of(), 0L))
                .toList();
        boolean rootComment = comment.getLevel() == 1;
        return new CommentResult(
                comment.getCommentNo(),
                comment.getPostNo(),
                comment.getAuthorId(),
                comment.getRootCommentNo(),
                comment.getParentCommentNo(),
                DatabaseSentinel.isNoneId(comment.getReplyToUserId())
                        ? DatabaseSentinel.NONE_ID
                        : comment.getReplyToUserId(),
                comment.getContent(),
                comment.getLevel(),
                comment.getCreatedAt(),
                rootComment ? replyTotalCount : 0L,
                rootComment ? REPLY_PAGE_SIZE : 0,
                rootComment ? totalPages(replyTotalCount, REPLY_PAGE_SIZE) : 0,
                replyResults);
    }

    private PendingCommentRecord toDeletedPendingRecord(CommentEntity comment, boolean persisted, OffsetDateTime now) {
        return new PendingCommentRecord(
                comment.getId(),
                comment.getCommentNo(),
                comment.getPostNo(),
                comment.getAuthorId(),
                comment.getRootCommentNo(),
                comment.getParentCommentNo(),
                comment.getReplyToUserId(),
                comment.getContent(),
                comment.getLevel(),
                CommentStatus.USER_DELETED,
                comment.getCreatedAt(),
                now,
                persisted);
    }

    private PendingCommentAcceptResult acceptPendingCreate(
            PendingCommentRecord record,
            String requestHash,
            String clientRequestId) {
        try {
            return commentCache.acceptCreate(record, requestHash, clientRequestId);
        } catch (RuntimeException ex) {
            throw commentCacheUnavailable("接收评论 pending 创建失败", ex);
        }
    }

    private Optional<PendingCommentAcceptResult> findPendingCreateResult(
            Long authorId,
            String clientRequestId,
            String requestHash) {
        try {
            return commentCache.findCreateResult(authorId, clientRequestId, requestHash);
        } catch (RuntimeException ex) {
            throw commentCacheUnavailable("读取评论创建幂等结果失败", ex);
        }
    }

    private void acceptPendingDelete(PendingCommentRecord record) {
        try {
            commentCache.acceptDelete(record);
        } catch (RuntimeException ex) {
            throw commentCacheUnavailable("接收评论 pending 删除失败", ex);
        }
    }

    private Optional<PendingCommentRecord> findPendingComment(Long commentNo) {
        try {
            return commentCache.findPendingComment(commentNo);
        } catch (RuntimeException ex) {
            throw commentCacheUnavailable("读取 pending 评论失败", ex);
        }
    }

    private boolean isPendingDeleted(Long commentNo) {
        try {
            return commentCache.isPendingDeleted(commentNo);
        } catch (RuntimeException ex) {
            throw commentCacheUnavailable("判断 pending 删除评论失败", ex);
        }
    }

    private BusinessException commentCacheUnavailable(String action, RuntimeException ex) {
        log.warn("{}，errorType={}, errorMessage={}", action, ex.getClass().getSimpleName(), ex.getMessage());
        return new BusinessException(PostErrorCode.COMMENT_TEMPORARILY_UNAVAILABLE, "评论服务暂时不可用");
    }

    private RootCommentCursor normalizeRootCommentCursor(OffsetDateTime cursorCreatedAt, Long cursorCommentNo) {
        OffsetDateTime normalizedCreatedAt = cursorCreatedAt == null
                ? DatabaseSentinel.NONE_TIME
                : cursorCreatedAt;
        Long normalizedCommentNo = cursorCommentNo == null ? DatabaseSentinel.NONE_ID : cursorCommentNo;
        boolean noCreatedAt = DatabaseSentinel.NONE_TIME.equals(normalizedCreatedAt);
        boolean noCommentNo = DatabaseSentinel.isNoneId(normalizedCommentNo);
        if (noCreatedAt && noCommentNo) {
            return new RootCommentCursor(DatabaseSentinel.NONE_TIME, DatabaseSentinel.NONE_ID);
        }
        if (noCreatedAt || noCommentNo || normalizedCommentNo <= 0) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "一级评论游标不完整");
        }
        return new RootCommentCursor(normalizedCreatedAt, normalizedCommentNo);
    }

    private RootCommentCursor nextRootCommentCursor(List<CommentEntity> roots, RootCommentCursor fallback) {
        if (roots.isEmpty()) {
            return fallback;
        }
        CommentEntity lastRoot = roots.get(roots.size() - 1);
        return new RootCommentCursor(lastRoot.getCreatedAt(), lastRoot.getCommentNo());
    }

    /**
     * 校验创建评论命令并规整正文。
     *
     * @param command 创建评论命令
     * @return 已规整的创建评论命令
     */
    private ValidCreateCommentCommand validateCreateCommentCommand(CreateCommentCommand command) {
        if (command == null) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "创建评论命令不能为空");
        }
        validatePositive(command.authorId(), "authorId");
        validatePositive(command.postNo(), "postNo");
        Long parentCommentNo = DatabaseSentinel.isNoneId(command.parentCommentNo())
                ? DatabaseSentinel.NONE_ID
                : command.parentCommentNo();
        if (!DatabaseSentinel.isNoneId(parentCommentNo)) {
            validatePositive(parentCommentNo, "parentCommentNo");
        }
        String content = command.content() == null ? "" : command.content().trim();
        if (content.isEmpty()) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "评论内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "评论内容过长");
        }
        if (isBlank(command.clientRequestId())) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "clientRequestId 不能为空");
        }
        return new ValidCreateCommentCommand(
                command.authorId(),
                command.postNo(),
                parentCommentNo,
                content,
                command.clientRequestId());
    }

    /**
     * 校验删除评论命令。
     *
     * @param command 删除评论命令
     */
    private void validateDeleteCommentCommand(DeleteCommentCommand command) {
        if (command == null) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "删除评论命令不能为空");
        }
        validatePositive(command.operatorId(), "operatorId");
        validatePositive(command.commentNo(), "commentNo");
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

    /**
     * 校验正整数参数。
     *
     * @param value 参数值
     * @param name 参数名
     */
    private void validatePositive(int value, String name) {
        if (value <= 0) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, name + " 必须为正整数");
        }
    }

    private int totalPages(long totalCount, int pageSize) {
        if (totalCount <= 0) {
            return 0;
        }
        return (int) ((totalCount + pageSize - 1) / pageSize);
    }

    /**
     * 判断字符串是否为空白。
     *
     * @param value 待判断字符串
     * @return 为空白时返回 true
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 计算创建评论请求哈希。
     *
     * @param command 已校验的创建评论命令
     * @return SHA-256 十六进制哈希
     */
    private String hashCreateCommentRequest(ValidCreateCommentCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = command.postNo()
                    + "|"
                    + command.parentCommentNo()
                    + "|"
                    + command.content();
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /**
     * 生成业务号或显式技术主键，避免对外暴露数据库自增语义。
     *
     * @param entity 参与生成的实体
     * @return 业务号
     */
    private Long nextBusinessNo(Object entity) {
        return identifierGenerator.nextId(entity).longValue();
    }

    /**
     * 已校验并规整后的创建评论命令。
     */
    private record ValidCreateCommentCommand(
            Long authorId,
            Long postNo,
            Long parentCommentNo,
            String content,
            String clientRequestId
    ) {
    }

    /**
     * 父评论和所属一级评论上下文。
     */
    private record ParentCommentContext(CommentEntity parent, CommentEntity root) {
    }

    /**
     * 一级评论游标，使用排序键 created_at + comment_no 表示已读取位置。
     */
    private record RootCommentCursor(OffsetDateTime createdAt, Long commentNo) {
    }

    /**
     * 评论解析结果，标识该评论是否已经落库并计入数据库评论数。
     */
    private record ResolvedComment(CommentEntity comment, boolean persisted) {
    }
}

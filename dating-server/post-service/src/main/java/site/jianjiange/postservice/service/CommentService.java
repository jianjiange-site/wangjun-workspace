package site.jianjiange.postservice.service;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import site.jianjiange.postservice.constant.DatabaseSentinel;
import site.jianjiange.postservice.entity.CommentEntity;
import site.jianjiange.postservice.entity.IdempotentRequestEntity;
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
 * 评论业务服务，负责编排创建、删除、一级评论列表和楼中楼回复列表。
 */
@Service
public class CommentService {

    private static final String CREATE_COMMENT_OPERATION = "CREATE_COMMENT";
    private static final int MAX_CONTENT_LENGTH = 1000;
    private static final int ROOT_COMMENT_PAGE_SIZE = 50;
    private static final int REPLY_PAGE_SIZE = 10;

    private final IdentifierGenerator identifierGenerator;
    private final CommentManager commentManager;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建评论业务服务。
     *
     * @param identifierGenerator 业务号生成器
     * @param commentManager 评论数据管理器
     * @param transactionTemplate 事务模板
     */
    public CommentService(
            IdentifierGenerator identifierGenerator,
            CommentManager commentManager,
            TransactionTemplate transactionTemplate) {
        this.identifierGenerator = identifierGenerator;
        this.commentManager = commentManager;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 创建一级评论或二级回复。
     *
     * @param command 创建评论命令
     * @return 创建评论结果
     */
    public CreateCommentResult createComment(CreateCommentCommand command) {
        ValidCreateCommentCommand validCommand = validateCreateCommentCommand(command);
        String requestHash = hashCreateCommentRequest(validCommand);
        IdempotentRequestEntity existing = commentManager.findIdempotentRequest(
                validCommand.authorId(), CREATE_COMMENT_OPERATION, validCommand.clientRequestId());
        if (existing != null) {
            if (!requestHash.equals(existing.getRequestHash())) {
                throw new BusinessException(PostErrorCode.IDEMPOTENT_CONFLICT, "幂等请求内容不一致");
            }
            return new CreateCommentResult(existing.getBizNo(), true);
        }

        try {
            return Objects.requireNonNull(transactionTemplate.execute(status ->
                    createCommentInTransaction(validCommand, requestHash)));
        } catch (DuplicateKeyException ex) {
            return recoverConcurrentIdempotentResult(validCommand, requestHash, ex);
        }
    }

    /**
     * 删除评论，仅允许评论作者软删除自己的正常评论，不级联删除回复。
     *
     * @param command 删除评论命令
     */
    @Transactional
    public void deleteComment(DeleteCommentCommand command) {
        validateDeleteCommentCommand(command);
        CommentEntity comment = commentManager.findByCommentNo(command.commentNo());
        if (comment == null || comment.getStatus() != CommentStatus.NORMAL) {
            throw new BusinessException(PostErrorCode.COMMENT_NOT_FOUND, "评论不存在");
        }
        if (!command.operatorId().equals(comment.getAuthorId())) {
            throw new BusinessException(PostErrorCode.COMMENT_FORBIDDEN, "只能删除自己的评论");
        }
        if (!commentManager.softDeleteComment(comment.getCommentNo(), command.operatorId(), OffsetDateTime.now())) {
            throw new BusinessException(PostErrorCode.COMMENT_NOT_FOUND, "评论不存在");
        }
        if (!commentManager.increaseCommentCount(comment.getPostNo(), -1L)) {
            throw new IllegalStateException("评论计数更新失败，postNo=" + comment.getPostNo());
        }
    }

    /**
     * 按页号查询帖子一级评论列表，每页固定 50 条。
     *
     * @param postNo 帖子业务号
     * @param pageNo 页号，从 1 开始
     * @return 一级评论分页结果
     */
    @Transactional(readOnly = true)
    public CommentPageResult listPostComments(Long postNo, int pageNo) {
        validatePositive(postNo, "postNo");
        validatePositive(pageNo, "pageNo");
        PostEntity post = commentManager.findPostByPostNo(postNo);
        if (post == null || post.getStatus() != PostStatus.PUBLISHED) {
            throw new BusinessException(PostErrorCode.POST_NOT_FOUND, "帖子不存在");
        }
        long totalCount = commentManager.countNormalRootComments(postNo);
        int totalPages = totalPages(totalCount, ROOT_COMMENT_PAGE_SIZE);
        List<CommentEntity> roots = pageNo > totalPages && totalPages > 0
                ? List.of()
                : commentManager.listNormalRootCommentsPage(postNo, pageNo, ROOT_COMMENT_PAGE_SIZE);
        Set<Long> rootIds = roots.stream()
                .map(CommentEntity::getId)
                .collect(Collectors.toSet());
        Map<Long, Long> replyCountsByRootId = commentManager.countNormalRepliesByRootIds(rootIds);
        Map<Long, CommentEntity> commentsById = resolveCommentsById(roots, List.of());
        List<CommentResult> results = roots.stream()
                .map(root -> toResult(
                        root,
                        commentsById,
                        List.of(),
                        replyCountsByRootId.getOrDefault(root.getId(), 0L)))
                .toList();
        return new CommentPageResult(
                results,
                pageNo,
                ROOT_COMMENT_PAGE_SIZE,
                totalCount,
                totalPages,
                pageNo > 1 && totalPages > 0,
                pageNo < totalPages);
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
        CommentEntity root = resolveNormalRootComment(rootCommentNo);
        long totalCount = commentManager.countNormalRepliesByRootId(root.getId());
        int totalPages = totalPages(totalCount, REPLY_PAGE_SIZE);
        List<CommentEntity> replies = pageNo > totalPages && totalPages > 0
                ? List.of()
                : commentManager.listNormalRepliesByRootIdPage(root.getId(), pageNo, REPLY_PAGE_SIZE);
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
        Map<Long, CommentEntity> commentsById = resolveCommentsById(List.of(root), replies);
        List<CommentResult> results = replies.stream()
                .map(reply -> toResult(reply, commentsById, List.of(), 0L))
                .toList();
        return new CommentPageResult(
                results,
                pageNo,
                REPLY_PAGE_SIZE,
                totalCount,
                totalPages,
                pageNo > 1,
                pageNo < totalPages);
    }

    /**
     * 在事务内创建评论、写幂等记录并更新帖子评论数。
     *
     * @param validCommand 已校验的创建评论命令
     * @param requestHash 请求哈希
     * @return 创建评论结果
     */
    private CreateCommentResult createCommentInTransaction(
            ValidCreateCommentCommand validCommand,
            String requestHash) {
        PostEntity post = commentManager.findPostByPostNo(validCommand.postNo());
        if (post == null) {
            throw new BusinessException(PostErrorCode.POST_NOT_FOUND, "帖子不存在");
        }
        if (post.getStatus() != PostStatus.PUBLISHED) {
            throw new BusinessException(PostErrorCode.POST_NOT_PUBLISHED, "只能评论公开可见帖子");
        }

        Optional<ParentCommentContext> parentContext = resolveParentComment(
                validCommand.parentCommentNo(), post.getPostNo());
        OffsetDateTime now = OffsetDateTime.now();
        CommentEntity comment = new CommentEntity();
        comment.setId(nextBusinessNo(comment));
        comment.setCommentNo(nextBusinessNo(comment));
        comment.setPostNo(post.getPostNo());
        comment.setAuthorId(validCommand.authorId());
        comment.setContent(validCommand.content());
        comment.setStatus(CommentStatus.NORMAL);
        comment.setCreatedAt(now);
        comment.setParentCommentId(DatabaseSentinel.NONE_ID);
        comment.setReplyToUserId(DatabaseSentinel.NONE_ID);
        comment.setDeletedAt(DatabaseSentinel.NONE_TIME);
        if (parentContext.isEmpty()) {
            comment.setLevel(1);
            comment.setRootCommentId(comment.getId());
        } else {
            ParentCommentContext context = parentContext.orElseThrow();
            comment.setLevel(2);
            comment.setRootCommentId(context.root().getId());
            comment.setParentCommentId(context.parent().getId());
            comment.setReplyToUserId(context.parent().getAuthorId());
        }

        commentManager.createComment(comment);
        IdempotentRequestEntity request = new IdempotentRequestEntity();
        request.setUserId(validCommand.authorId());
        request.setOperationType(CREATE_COMMENT_OPERATION);
        request.setClientRequestId(validCommand.clientRequestId());
        request.setRequestHash(requestHash);
        request.setBizNo(comment.getCommentNo());
        request.setResponseSnapshot("{\"commentNo\":" + comment.getCommentNo() + "}");
        request.setCreatedAt(now);
        request.setExpireAt(now.plusDays(1));
        commentManager.createIdempotentRequest(request);
        if (!commentManager.increaseCommentCount(post.getPostNo(), 1L)) {
            throw new IllegalStateException("评论计数更新失败，postNo=" + post.getPostNo());
        }
        return new CreateCommentResult(comment.getCommentNo(), false);
    }

    /**
     * 处理并发重复创建时的幂等恢复；如果失败不是同一幂等请求导致，则继续抛出原异常。
     *
     * @param validCommand 已校验的创建评论命令
     * @param requestHash 请求哈希
     * @param cause 原始异常
     * @return 并发幂等恢复后的创建结果
     */
    private CreateCommentResult recoverConcurrentIdempotentResult(
            ValidCreateCommentCommand validCommand,
            String requestHash,
            RuntimeException cause) {
        IdempotentRequestEntity existing = commentManager.findIdempotentRequest(
                validCommand.authorId(), CREATE_COMMENT_OPERATION, validCommand.clientRequestId());
        if (existing == null) {
            throw cause;
        }
        if (!requestHash.equals(existing.getRequestHash())) {
            throw new BusinessException(PostErrorCode.IDEMPOTENT_CONFLICT, "幂等请求内容不一致");
        }
        return new CreateCommentResult(existing.getBizNo(), true);
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
        CommentEntity parent = commentManager.findByCommentNo(parentCommentNo);
        if (parent == null
                || parent.getStatus() != CommentStatus.NORMAL
                || !postNo.equals(parent.getPostNo())) {
            throw new BusinessException(PostErrorCode.COMMENT_NOT_FOUND, "父评论不存在");
        }
        Optional<CommentEntity> root = parent.getLevel() == 1
                ? Optional.of(parent)
                : commentManager.listByIds(List.of(parent.getRootCommentId())).stream().findFirst();
        if (root.isEmpty()) {
            throw new BusinessException(PostErrorCode.COMMENT_NOT_FOUND, "一级评论不存在");
        }
        CommentEntity rootComment = root.orElseThrow();
        if (rootComment.getLevel() != 1
                || rootComment.getStatus() != CommentStatus.NORMAL
                || !postNo.equals(rootComment.getPostNo())) {
            throw new BusinessException(PostErrorCode.COMMENT_NOT_FOUND, "一级评论不存在");
        }
        return Optional.of(new ParentCommentContext(parent, rootComment));
    }

    /**
     * 校验并解析正常一级评论。
     *
     * @param rootCommentNo 一级评论业务号
     * @return 正常一级评论实体
     */
    private CommentEntity resolveNormalRootComment(Long rootCommentNo) {
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
     * 构造结果转换需要的评论 ID 索引，补齐 parentCommentId 指向但当前列表中不存在的评论。
     *
     * @param knownComments 已知评论集合
     * @param resultComments 待转换评论集合
     * @return 以评论技术主键为 key 的评论索引
     */
    private Map<Long, CommentEntity> resolveCommentsById(
            Collection<CommentEntity> knownComments,
            Collection<CommentEntity> resultComments) {
        Map<Long, CommentEntity> commentsById = new HashMap<>();
        for (CommentEntity comment : flatten(List.of(knownComments, resultComments))) {
            commentsById.put(comment.getId(), comment);
        }
        Set<Long> missingIds = new HashSet<>();
        for (CommentEntity comment : resultComments) {
            addMissingId(comment.getRootCommentId(), commentsById, missingIds);
            addMissingId(comment.getParentCommentId(), commentsById, missingIds);
        }
        for (CommentEntity comment : commentManager.listByIds(missingIds)) {
            commentsById.put(comment.getId(), comment);
        }
        return commentsById;
    }

    /**
     * 转换评论实体为业务结果。
     *
     * @param comment 评论实体
     * @param commentsById 评论 ID 索引
     * @param replies 内嵌回复列表，当前一级评论分页不使用
     * @return 评论结果
     */
    private CommentResult toResult(
            CommentEntity comment,
            Map<Long, CommentEntity> commentsById,
            List<CommentEntity> replies,
            long replyTotalCount) {
        List<CommentResult> replyResults = replies.stream()
                .map(reply -> toResult(reply, commentsById, List.of(), 0L))
                .toList();
        boolean rootComment = comment.getLevel() == 1;
        return new CommentResult(
                comment.getCommentNo(),
                comment.getPostNo(),
                comment.getAuthorId(),
                resolveCommentNo(comment.getRootCommentId(), commentsById),
                resolveCommentNo(comment.getParentCommentId(), commentsById),
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

    private Long resolveCommentNo(Long commentId, Map<Long, CommentEntity> commentsById) {
        if (DatabaseSentinel.isNoneId(commentId)) {
            return DatabaseSentinel.NONE_ID;
        }
        CommentEntity comment = commentsById.get(commentId);
        return comment == null ? DatabaseSentinel.NONE_ID : comment.getCommentNo();
    }

    private void addMissingId(Long commentId, Map<Long, CommentEntity> commentsById, Set<Long> missingIds) {
        if (!DatabaseSentinel.isNoneId(commentId) && !commentsById.containsKey(commentId)) {
            missingIds.add(commentId);
        }
    }

    private List<CommentEntity> flatten(Collection<? extends Collection<CommentEntity>> groups) {
        return groups.stream()
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .toList();
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
}

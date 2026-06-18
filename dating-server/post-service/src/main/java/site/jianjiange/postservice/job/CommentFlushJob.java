package site.jianjiange.postservice.job;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.cache.CommentCache;
import site.jianjiange.postservice.cache.PendingCommentRecord;
import site.jianjiange.postservice.constant.DatabaseSentinel;
import site.jianjiange.postservice.entity.CommentEntity;
import site.jianjiange.postservice.enums.CommentStatus;
import site.jianjiange.postservice.manager.CommentManager;

/**
 * 评论回写任务，将 Redis pending 评论事实最终写入 PostgreSQL。
 */
@Component
public class CommentFlushJob {

    private static final Logger log = LoggerFactory.getLogger(CommentFlushJob.class);

    private final CommentCache commentCache;
    private final CommentManager commentManager;

    /**
     * 创建评论回写任务。
     *
     * @param commentCache 评论 pending 缓存
     * @param commentManager 评论数据管理器
     */
    public CommentFlushJob(CommentCache commentCache, CommentManager commentManager) {
        this.commentCache = commentCache;
        this.commentManager = commentManager;
    }

    /**
     * 定时回写评论创建和删除事实。
     */
    @Scheduled(initialDelayString = "${dating.comment.flush-initial-delay-ms:60000}",
            fixedDelayString = "${dating.comment.flush-fixed-delay-ms:10000}")
    public void flushComments() {
        Optional<String> lockToken = commentCache.acquireFlushLock();
        if (lockToken.isEmpty()) {
            return;
        }
        String ownerToken = lockToken.orElseThrow();
        try {
            flushPendingCreates();
            flushPendingDeletes();
        } catch (RuntimeException ex) {
            log.warn("评论回写任务执行失败，errorType={}, errorMessage={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
        } finally {
            commentCache.releaseFlushLock(ownerToken);
        }
    }

    /**
     * 回写仍处于正常状态的创建事实；已被删除的创建事实交给删除阶段按最终状态处理。
     */
    private void flushPendingCreates() {
        for (Long commentNo : commentCache.listPendingCreateCommentNos()) {
            flushCreate(commentNo, new HashSet<>(), false);
        }
    }

    /**
     * 回写删除事实，支持已落库评论删除和创建 pending 后立刻删除两种路径。
     */
    private void flushPendingDeletes() {
        for (Long commentNo : commentCache.listPendingDeleteCommentNos()) {
            Optional<PendingCommentRecord> pending = commentCache.findPendingComment(commentNo);
            if (pending.isEmpty()) {
                log.warn("评论删除 pending 数据缺失，commentNo={}", commentNo);
                commentCache.discardPendingDelete(commentNo);
                continue;
            }

            PendingCommentRecord record = pending.orElseThrow();
            boolean hadPendingCreate = commentCache.hasPendingCreate(commentNo);
            if (hadPendingCreate && !flushCreate(commentNo, new HashSet<>(), true)) {
                continue;
            }

            CommentEntity dbComment = commentManager.findByCommentNo(commentNo);
            if (dbComment == null) {
                log.warn("评论删除回写未找到数据库记录，commentNo={}, hadPendingCreate={}", commentNo, hadPendingCreate);
                if (!hadPendingCreate) {
                    commentCache.discardPendingDelete(commentNo);
                }
                continue;
            }

            if (dbComment.getStatus() == CommentStatus.NORMAL) {
                commentManager.softDeleteCommentWithCount(
                        record.commentNo(), record.authorId(), record.postNo(), record.deletedAt());
            }
            commentCache.ackDelete(record);
        }
    }

    /**
     * 回写单条创建事实，并在二级回复前保证根评论和被回复评论已经落库。
     *
     * @param commentNo 评论业务号
     * @param visiting 当前递归链路，用于防止坏数据形成循环
     * @param includeDeleted 是否允许按删除状态落库
     * @return 回写成功或无需回写时返回 true
     */
    private boolean flushCreate(Long commentNo, Set<Long> visiting, boolean includeDeleted) {
        if (!visiting.add(commentNo)) {
            log.warn("评论创建回写存在循环依赖，commentNo={}", commentNo);
            return false;
        }
        try {
            Optional<PendingCommentRecord> pending = commentCache.findPendingComment(commentNo);
            if (pending.isEmpty()) {
                commentCache.discardPendingCreate(commentNo);
                return true;
            }
            PendingCommentRecord record = pending.orElseThrow();
            if (record.status() != CommentStatus.NORMAL && !includeDeleted) {
                return false;
            }
            CommentEntity existing = commentManager.findByCommentNo(commentNo);
            if (existing != null) {
                commentCache.ackCreate(record);
                return true;
            }
            if (record.level() == 2 && !flushDependencies(record, visiting)) {
                return false;
            }

            commentManager.createCommentWithCount(record.toEntity(), record.status() == CommentStatus.NORMAL);
            commentCache.ackCreate(record);
            return true;
        } finally {
            visiting.remove(commentNo);
        }
    }

    /**
     * 二级回复回写前先落库根评论和被回复评论，保证关系 ID 可在数据库中追溯。
     */
    private boolean flushDependencies(PendingCommentRecord record, Set<Long> visiting) {
        List<Long> dependencyCommentNos = Objects.equals(record.rootCommentNo(), record.parentCommentNo())
                ? List.of(record.rootCommentNo())
                : List.of(record.rootCommentNo(), record.parentCommentNo());
        for (Long dependencyCommentNo : dependencyCommentNos) {
            if (DatabaseSentinel.isNoneId(dependencyCommentNo)) {
                continue;
            }
            if (commentManager.findByCommentNo(dependencyCommentNo) != null) {
                continue;
            }
            Optional<PendingCommentRecord> dependency = commentCache.findPendingComment(dependencyCommentNo);
            if (dependency.isEmpty()) {
                log.warn("评论创建依赖缺失，commentNo={}, dependencyCommentNo={}",
                        record.commentNo(), dependencyCommentNo);
                return false;
            }
            if (!flushCreate(dependencyCommentNo, visiting, true)) {
                return false;
            }
        }
        return true;
    }
}

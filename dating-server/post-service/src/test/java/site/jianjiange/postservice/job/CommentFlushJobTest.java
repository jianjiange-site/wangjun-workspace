package site.jianjiange.postservice.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import site.jianjiange.postservice.cache.CommentCache;
import site.jianjiange.postservice.cache.PendingCommentRecord;
import site.jianjiange.postservice.constant.DatabaseSentinel;
import site.jianjiange.postservice.entity.CommentEntity;
import site.jianjiange.postservice.enums.CommentStatus;
import site.jianjiange.postservice.manager.CommentManager;

/**
 * 评论回写任务测试，覆盖 pending 创建、创建后删除和已落库删除的计数处理。
 */
class CommentFlushJobTest {

    /**
     * 验证正常创建 pending 会插入数据库、增加评论数并确认 create 队列。
     */
    @Test
    void flushCommentsWritesNormalCreateAndAcknowledgesQueue() {
        CommentCache commentCache = mock(CommentCache.class);
        CommentManager commentManager = mock(CommentManager.class);
        CommentFlushJob job = new CommentFlushJob(commentCache, commentManager);
        PendingCommentRecord record = rootRecord(CommentStatus.NORMAL, DatabaseSentinel.NONE_TIME, false);
        String lockToken = "comment-flush-token";

        when(commentCache.acquireFlushLock()).thenReturn(Optional.of(lockToken));
        when(commentCache.listPendingCreateCommentNos()).thenReturn(List.of(record.commentNo()));
        when(commentCache.listPendingDeleteCommentNos()).thenReturn(List.of());
        when(commentCache.findPendingComment(record.commentNo())).thenReturn(Optional.of(record));
        when(commentManager.findByCommentNo(record.commentNo())).thenReturn(null);

        job.flushComments();

        verify(commentManager).createCommentWithCount(any(CommentEntity.class), eq(true));
        verify(commentCache).ackCreate(record);
        verify(commentCache).releaseFlushLock(lockToken);
    }

    /**
     * 验证创建 pending 又被删除时，按 USER_DELETED 最终状态落库且不增加评论数。
     */
    @Test
    void flushCommentsWritesDeletedCreateAndAcknowledgesDelete() {
        CommentCache commentCache = mock(CommentCache.class);
        CommentManager commentManager = mock(CommentManager.class);
        CommentFlushJob job = new CommentFlushJob(commentCache, commentManager);
        OffsetDateTime deletedAt = OffsetDateTime.now();
        PendingCommentRecord record = rootRecord(CommentStatus.USER_DELETED, deletedAt, false);
        CommentEntity deletedComment = record.toEntity();

        when(commentCache.acquireFlushLock()).thenReturn(Optional.of("comment-flush-token"));
        when(commentCache.listPendingCreateCommentNos()).thenReturn(List.of(record.commentNo()));
        when(commentCache.listPendingDeleteCommentNos()).thenReturn(List.of(record.commentNo()));
        when(commentCache.findPendingComment(record.commentNo())).thenReturn(Optional.of(record));
        when(commentCache.hasPendingCreate(record.commentNo())).thenReturn(true);
        when(commentManager.findByCommentNo(record.commentNo())).thenReturn(null, deletedComment);

        job.flushComments();

        verify(commentManager).createCommentWithCount(any(CommentEntity.class), eq(false));
        verify(commentManager, never()).softDeleteCommentWithCount(any(), any(), any(), any());
        verify(commentCache).ackCreate(record);
        verify(commentCache).ackDelete(record);
    }

    /**
     * 验证已落库正常评论删除时会软删数据库、扣减评论数并确认 delete 队列。
     */
    @Test
    void flushCommentsDeletesPersistedCommentAndAcknowledgesDelete() {
        CommentCache commentCache = mock(CommentCache.class);
        CommentManager commentManager = mock(CommentManager.class);
        CommentFlushJob job = new CommentFlushJob(commentCache, commentManager);
        OffsetDateTime deletedAt = OffsetDateTime.now();
        PendingCommentRecord record = rootRecord(CommentStatus.USER_DELETED, deletedAt, true);
        CommentEntity normalComment = rootRecord(CommentStatus.NORMAL, DatabaseSentinel.NONE_TIME, true).toEntity();

        when(commentCache.acquireFlushLock()).thenReturn(Optional.of("comment-flush-token"));
        when(commentCache.listPendingCreateCommentNos()).thenReturn(List.of());
        when(commentCache.listPendingDeleteCommentNos()).thenReturn(List.of(record.commentNo()));
        when(commentCache.findPendingComment(record.commentNo())).thenReturn(Optional.of(record));
        when(commentCache.hasPendingCreate(record.commentNo())).thenReturn(false);
        when(commentManager.findByCommentNo(record.commentNo())).thenReturn(normalComment);

        job.flushComments();

        verify(commentManager).softDeleteCommentWithCount(
                record.commentNo(), record.authorId(), record.postNo(), record.deletedAt());
        verify(commentCache).ackDelete(record);
    }

    /**
     * 验证未获得回写锁时直接跳过，避免多实例重复回写。
     */
    @Test
    void flushCommentsSkipsWhenLockNotAcquired() {
        CommentCache commentCache = mock(CommentCache.class);
        CommentManager commentManager = mock(CommentManager.class);
        CommentFlushJob job = new CommentFlushJob(commentCache, commentManager);
        when(commentCache.acquireFlushLock()).thenReturn(Optional.empty());

        job.flushComments();

        verify(commentCache, never()).listPendingCreateCommentNos();
        verify(commentManager, never()).createCommentWithCount(any(), eq(true));
        verify(commentCache, never()).releaseFlushLock(any());
    }

    private PendingCommentRecord rootRecord(CommentStatus status, OffsetDateTime deletedAt, boolean persisted) {
        return new PendingCommentRecord(
                101L,
                9100101L,
                91001L,
                3001L,
                9100101L,
                DatabaseSentinel.NONE_ID,
                DatabaseSentinel.NONE_ID,
                "root",
                1,
                status,
                OffsetDateTime.now().minusMinutes(1),
                deletedAt,
                persisted);
    }
}

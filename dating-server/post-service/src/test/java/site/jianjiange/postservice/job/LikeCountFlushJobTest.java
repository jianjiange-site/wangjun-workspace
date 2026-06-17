package site.jianjiange.postservice.job;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import site.jianjiange.postservice.cache.LikeCountCache;
import site.jianjiange.postservice.manager.LikeManager;

/**
 * 点赞回写任务测试，覆盖 Redis delta 转移和计数累加流程。
 */
class LikeCountFlushJobTest {

    /**
     * 验证回写任务会将 delta 转为 flushing，并按 delta 累加计数后删除 flushing key。
     */
    @Test
    void flushLikeCountsMovesDeltaAndUpdatesDatabase() {
        LikeCountCache likeCountCache = mock(LikeCountCache.class);
        LikeManager likeManager = mock(LikeManager.class);
        LikeCountFlushJob job = new LikeCountFlushJob(likeCountCache, likeManager);
        String deltaKey = "wangjun:post:like:delta:91001";
        String flushingKey = "wangjun:post:like:flushing:91001";
        when(likeCountCache.acquireFlushLock()).thenReturn(true);
        when(likeCountCache.scanFlushingKeys())
                .thenReturn(Set.of())
                .thenReturn(Set.of(flushingKey));
        when(likeCountCache.scanDeltaKeys()).thenReturn(Set.of(deltaKey));
        when(likeCountCache.parsePostNoFromDeltaKey(deltaKey)).thenReturn(Optional.of(91001L));
        when(likeCountCache.moveLikeBuffersToFlushing(91001L)).thenReturn(true);
        when(likeCountCache.parsePostNoFromFlushingKey(flushingKey)).thenReturn(Optional.of(91001L));
        when(likeCountCache.readFlushingLikeDelta(91001L)).thenReturn(3L);
        when(likeManager.increaseLikeCount(91001L, 3L)).thenReturn(true);

        job.flushLikeCounts();

        verify(likeCountCache).moveLikeBuffersToFlushing(91001L);
        verify(likeManager).increaseLikeCount(91001L, 3L);
        verify(likeCountCache).deleteFlushingBuffers(91001L);
        verify(likeCountCache).releaseFlushLock();
    }

    /**
     * 验证未获得回写锁时直接跳过，避免多实例重复回写。
     */
    @Test
    void flushLikeCountsSkipsWhenLockNotAcquired() {
        LikeCountCache likeCountCache = mock(LikeCountCache.class);
        LikeManager likeManager = mock(LikeManager.class);
        LikeCountFlushJob job = new LikeCountFlushJob(likeCountCache, likeManager);
        when(likeCountCache.acquireFlushLock()).thenReturn(false);

        job.flushLikeCounts();

        verify(likeCountCache, never()).scanDeltaKeys();
        verify(likeManager, never()).increaseLikeCount(anyLong(), anyLong());
    }
}

package site.jianjiange.postservice.job;

import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.cache.LikeCountCache;
import site.jianjiange.postservice.manager.LikeManager;

/**
 * 点赞回写任务，将 Redis 点赞增量累加到 PostgreSQL post.like_count。
 */
@Component
public class LikeCountFlushJob {

    private static final Logger log = LoggerFactory.getLogger(LikeCountFlushJob.class);

    private final LikeCountCache likeCountCache;
    private final LikeManager likeManager;

    /**
     * 创建点赞计数回写任务。
     *
     * @param likeCountCache 点赞计数缓存
     * @param likeManager 点赞数据管理器
     */
    public LikeCountFlushJob(LikeCountCache likeCountCache, LikeManager likeManager) {
        this.likeCountCache = likeCountCache;
        this.likeManager = likeManager;
    }

    /**
     * 定时回写点赞计数。
     */
    @Scheduled(initialDelayString = "${dating.like.flush-initial-delay-ms:60000}",
            fixedDelayString = "${dating.like.flush-fixed-delay-ms:10000}")
    public void flushLikeCounts() {
        Optional<String> lockToken = likeCountCache.acquireFlushLock();
        if (lockToken.isEmpty()) {
            return;
        }
        String ownerToken = lockToken.orElseThrow();
        try {
            flushExistingFlushingKeys();
            transferDeltaKeys();
            flushExistingFlushingKeys();
        } catch (RuntimeException ex) {
            log.warn("点赞计数回写任务执行失败，errorType={}, errorMessage={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
        } finally {
            likeCountCache.releaseFlushLock(ownerToken);
        }
    }

    /**
     * 回写已经转移到 flushing 状态的点赞计数。
     */
    private void flushExistingFlushingKeys() {
        Set<String> flushingKeys = likeCountCache.scanFlushingKeys();
        for (String flushingKey : flushingKeys) {
            flushOneFlushingKey(flushingKey);
        }
    }

    /**
     * 将待回写 delta 转移为 flushing key，避免回写期间新点赞和本次回写互相覆盖。
     */
    private void transferDeltaKeys() {
        Set<String> deltaKeys = likeCountCache.scanDeltaKeys();
        for (String deltaKey : deltaKeys) {
            Optional<Long> postNo = likeCountCache.parsePostNoFromDeltaKey(deltaKey);
            postNo.ifPresent(likeCountCache::moveLikeBuffersToFlushing);
        }
    }

    /**
     * 回写单个 flushing 计数 key。
     *
     * @param flushingKey 正在回写的标记 key
     */
    private void flushOneFlushingKey(String flushingKey) {
        Optional<Long> postNo = likeCountCache.parsePostNoFromFlushingKey(flushingKey);
        if (postNo.isEmpty()) {
            likeCountCache.deleteKey(flushingKey);
            return;
        }
        long delta = likeCountCache.readFlushingLikeDelta(postNo.get());
        if (delta <= 0) {
            likeCountCache.deleteFlushingBuffers(postNo.get());
            return;
        }
        if (!likeManager.increaseLikeCount(postNo.get(), delta)) {
            throw new IllegalStateException("点赞计数回写失败，postNo=" + postNo.get());
        }
        likeCountCache.deleteFlushingBuffers(postNo.get());
    }
}

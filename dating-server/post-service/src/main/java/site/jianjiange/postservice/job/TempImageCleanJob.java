package site.jianjiange.postservice.job;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.cache.ImageCleanupCache;
import site.jianjiange.postservice.entity.PostImageEntity;
import site.jianjiange.postservice.manager.PostImageManager;
import site.jianjiange.postservice.storage.ObjectStorageClient;
import site.jianjiange.postservice.storage.ObjectStorageException;

/**
 * TEMP 图片清理任务，删除过期未绑定图片的对象存储文件。
 */
@Component
public class TempImageCleanJob {

    private static final Logger log = LoggerFactory.getLogger(TempImageCleanJob.class);
    private static final int CLEAN_BATCH_LIMIT = 100;
    private static final int MAX_DELETE_RETRY_COUNT = 3;
    private static final Duration CLEANING_STALE_AFTER = Duration.ofMinutes(15);

    private final ImageCleanupCache imageCleanupCache;
    private final PostImageManager postImageManager;
    private final ObjectStorageClient objectStorageClient;

    /**
     * 创建 TEMP 图片清理任务。
     *
     * @param imageCleanupCache 图片清理锁缓存
     * @param postImageManager 图片数据管理器
     * @param objectStorageClient 对象存储客户端
     */
    public TempImageCleanJob(
            ImageCleanupCache imageCleanupCache,
            PostImageManager postImageManager,
            ObjectStorageClient objectStorageClient) {
        this.imageCleanupCache = imageCleanupCache;
        this.postImageManager = postImageManager;
        this.objectStorageClient = objectStorageClient;
    }

    /**
     * 定时清理过期 TEMP 图片。
     */
    @Scheduled(initialDelayString = "${dating.image-clean.initial-delay-ms:60000}",
            fixedDelayString = "${dating.image-clean.fixed-delay-ms:60000}")
    public void cleanTempImages() {
        Optional<String> lockToken = imageCleanupCache.acquireCleanLock();
        if (lockToken.isEmpty()) {
            return;
        }
        String ownerToken = lockToken.orElseThrow();
        try {
            cleanClaimedImages();
        } catch (RuntimeException ex) {
            log.warn("TEMP 图片清理任务执行失败，errorType={}, errorMessage={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
        } finally {
            imageCleanupCache.releaseCleanLock(ownerToken);
        }
    }

    /**
     * 认领并清理一批可删除图片。
     */
    private void cleanClaimedImages() {
        OffsetDateTime now = OffsetDateTime.now();
        postImageManager.markStaleCleaningFailed(
                now.minus(CLEANING_STALE_AFTER),
                now,
                MAX_DELETE_RETRY_COUNT);
        List<PostImageEntity> images = postImageManager.claimCleanableImages(
                now,
                CLEAN_BATCH_LIMIT,
                MAX_DELETE_RETRY_COUNT);
        for (PostImageEntity image : images) {
            cleanOneImage(image);
        }
    }

    /**
     * 删除单张图片对象，并按结果更新清理状态。
     *
     * @param image 已认领的图片
     */
    private void cleanOneImage(PostImageEntity image) {
        try {
            objectStorageClient.deleteObject(image.getBucket(), image.getObjectKey());
            postImageManager.markImageCleaned(image.getId(), OffsetDateTime.now());
        } catch (ObjectStorageException ex) {
            log.warn("TEMP 图片对象删除失败，imageNo={}, objectKey={}, errorType={}, errorMessage={}",
                    image.getImageNo(), image.getObjectKey(), ex.getClass().getSimpleName(), ex.getMessage());
            postImageManager.markImageDeleteFailed(image.getId(), OffsetDateTime.now());
        }
    }
}

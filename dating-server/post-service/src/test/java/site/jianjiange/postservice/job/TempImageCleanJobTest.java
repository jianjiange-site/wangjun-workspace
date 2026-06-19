package site.jianjiange.postservice.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import site.jianjiange.postservice.cache.ImageCleanupCache;
import site.jianjiange.postservice.entity.PostImageEntity;
import site.jianjiange.postservice.enums.ImageStatus;
import site.jianjiange.postservice.manager.PostImageManager;
import site.jianjiange.postservice.storage.ObjectStorageClient;
import site.jianjiange.postservice.storage.ObjectStorageException;

/**
 * TEMP 图片清理任务测试，覆盖对象删除成功、失败重试和分布式锁跳过。
 */
class TempImageCleanJobTest {

    @Test
    void cleanTempImagesDeletesObjectAndMarksCleaned() {
        ImageCleanupCache imageCleanupCache = mock(ImageCleanupCache.class);
        PostImageManager postImageManager = mock(PostImageManager.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        TempImageCleanJob job = new TempImageCleanJob(imageCleanupCache, postImageManager, objectStorageClient);
        PostImageEntity image = tempImage();
        String lockToken = "image-clean-token";
        when(imageCleanupCache.acquireCleanLock()).thenReturn(Optional.of(lockToken));
        when(postImageManager.claimCleanableImages(any(), anyInt(), anyInt())).thenReturn(List.of(image));

        job.cleanTempImages();

        verify(objectStorageClient).deleteObject("wangjun-dating", "wangjun-tmp/post/1001/7001.jpg");
        verify(postImageManager).markImageCleaned(any(), any());
        verify(postImageManager, never()).markImageDeleteFailed(any(), any());
        verify(imageCleanupCache).releaseCleanLock(lockToken);
    }

    @Test
    void cleanTempImagesMarksDeleteFailedWhenStorageDeleteFails() {
        ImageCleanupCache imageCleanupCache = mock(ImageCleanupCache.class);
        PostImageManager postImageManager = mock(PostImageManager.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        TempImageCleanJob job = new TempImageCleanJob(imageCleanupCache, postImageManager, objectStorageClient);
        PostImageEntity image = tempImage();
        when(imageCleanupCache.acquireCleanLock()).thenReturn(Optional.of("image-clean-token"));
        when(postImageManager.claimCleanableImages(any(), anyInt(), anyInt())).thenReturn(List.of(image));
        doThrow(new ObjectStorageException("delete failed", null))
                .when(objectStorageClient)
                .deleteObject(anyString(), anyString());

        job.cleanTempImages();

        verify(postImageManager).markImageDeleteFailed(any(), any());
        verify(postImageManager, never()).markImageCleaned(any(), any());
    }

    @Test
    void cleanTempImagesSkipsWhenLockNotAcquired() {
        ImageCleanupCache imageCleanupCache = mock(ImageCleanupCache.class);
        PostImageManager postImageManager = mock(PostImageManager.class);
        ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
        TempImageCleanJob job = new TempImageCleanJob(imageCleanupCache, postImageManager, objectStorageClient);
        when(imageCleanupCache.acquireCleanLock()).thenReturn(Optional.empty());

        job.cleanTempImages();

        verify(postImageManager, never()).claimCleanableImages(any(), anyInt(), anyInt());
        verify(objectStorageClient, never()).deleteObject(anyString(), anyString());
        verify(imageCleanupCache, never()).releaseCleanLock(anyString());
    }

    private PostImageEntity tempImage() {
        PostImageEntity image = new PostImageEntity();
        image.setId(101L);
        image.setImageNo(7001L);
        image.setUserId(1001L);
        image.setBucket("wangjun-dating");
        image.setObjectKey("wangjun-tmp/post/1001/7001.jpg");
        image.setStatus(ImageStatus.CLEANING);
        image.setRetryCount(0);
        image.setUploadExpireAt(OffsetDateTime.now().minusHours(1));
        image.setUpdatedAt(OffsetDateTime.now());
        return image;
    }
}

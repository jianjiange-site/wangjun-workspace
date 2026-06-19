package site.jianjiange.postservice.storage;

import java.time.Duration;

/**
 * 对象存储客户端抽象，用于隔离业务层与 MinIO SDK 的直接依赖。
 */
public interface ObjectStorageClient {

    /**
     * 查询对象元数据。
     *
     * @param bucket 存储桶
     * @param objectKey 对象 Key
     * @return 对象元数据
     */
    ObjectMetadata statObject(String bucket, String objectKey);

    /**
     * 创建对象上传预签名 URL。
     *
     * @param bucket 存储桶
     * @param objectKey 对象 Key
     * @param contentType 内容类型
     * @param ttl URL 有效期
     * @return 可供客户端直传对象存储的 URL
     */
    String createUploadUrl(String bucket, String objectKey, String contentType, Duration ttl);

    /**
     * 删除对象。
     *
     * @param bucket 存储桶
     * @param objectKey 对象 Key
     */
    void deleteObject(String bucket, String objectKey);
}

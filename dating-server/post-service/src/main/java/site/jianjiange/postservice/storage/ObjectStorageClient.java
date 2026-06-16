package site.jianjiange.postservice.storage;

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
}

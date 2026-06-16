package site.jianjiange.postservice.storage;

/**
 * 对象存储通用异常，表示 MinIO 或 S3 兼容存储操作失败。
 */
public class ObjectStorageException extends RuntimeException {

    /**
     * 创建对象存储异常。
     *
     * @param message 异常信息
     * @param cause 原始异常
     */
    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

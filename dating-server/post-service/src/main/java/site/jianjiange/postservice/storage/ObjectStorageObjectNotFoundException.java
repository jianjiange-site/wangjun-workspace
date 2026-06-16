package site.jianjiange.postservice.storage;

/**
 * 对象不存在异常，表示数据库中的图片记录还没有真实上传到对象存储。
 */
public class ObjectStorageObjectNotFoundException extends ObjectStorageException {

    /**
     * 创建对象不存在异常。
     *
     * @param message 异常信息
     * @param cause 原始异常
     */
    public ObjectStorageObjectNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}

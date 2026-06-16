package site.jianjiange.postservice.storage;

/**
 * 对象存储元数据，表示对象存储 statObject 返回的关键校验信息。
 *
 * @param contentType 对象内容类型
 * @param sizeBytes 对象大小，单位字节
 * @param etag 对象 ETag
 */
public record ObjectMetadata(
        String contentType,
        long sizeBytes,
        String etag
) {
}
